package com.lazybone.hazmat.service;

import com.lazybone.hazmat.common.BusinessException;
import com.lazybone.hazmat.common.DocumentNoGenerator;
import com.lazybone.hazmat.common.ResourceNotFoundException;
import com.lazybone.hazmat.dto.StocktakeCreateRequest;
import com.lazybone.hazmat.dto.StocktakeItemRequest;
import com.lazybone.hazmat.dto.StocktakeItemResponse;
import com.lazybone.hazmat.dto.StocktakeResponse;
import com.lazybone.hazmat.entity.Chemical;
import com.lazybone.hazmat.entity.ChemicalBatch;
import com.lazybone.hazmat.entity.StockLedger;
import com.lazybone.hazmat.entity.Stocktake;
import com.lazybone.hazmat.entity.StocktakeItem;
import com.lazybone.hazmat.entity.StocktakeStatus;
import com.lazybone.hazmat.repository.ChemicalBatchRepository;
import com.lazybone.hazmat.repository.ChemicalRepository;
import com.lazybone.hazmat.repository.StockLedgerRepository;
import com.lazybone.hazmat.repository.StocktakeItemRepository;
import com.lazybone.hazmat.repository.StocktakeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 库存盘点：建单 → 录入实盘量 → 提交 → 确认调账。
 *
 * <b>这个类要解决的核心问题：</b>系统账和柜子里的实物会漂移。漏登记的取用、倾洒、
 * 拿错瓶子，都不会自动出现在流水里；而所有下游（效期预警、报表、库存视图）
 * 都读同一个数字。盘点是把两条线对上的唯一手段，差异必须落成 ADJUST 流水，
 * 不能直接改数字——台账只增不改。
 *
 * <b>三个关键决定：</b>
 * <ol>
 *   <li><b>账面量在建单时冻结</b>（{@code StocktakeItem.bookQuantity}）。实盘量的语义是
 *       「相对于我数的那一刻」，基准必须跟着冻结。</li>
 *   <li><b>确认时用条件更新校验基准没变</b>（{@link ChemicalBatchRepository#adjustTo}）。
 *       盘点期间有人出库，这张单就过期了，必须重盘——不能拿错误基准调账。</li>
 *   <li><b>整单原子</b>。任何一条对不上，整张单回滚，状态留在 SUBMITTED。
 *       不允许「盘了 50 条，确认了 47 条」——那样剩下的 3 条会基于一个已经失效的快照。</li>
 * </ol>
 */
@Slf4j
@Service
public class StocktakeService {

    /** 「未完成」的盘点状态。建单时要挡住同一批次被两张单同时盘 */
    private static final List<StocktakeStatus> OPEN_STATUSES =
            List.of(StocktakeStatus.DRAFT, StocktakeStatus.SUBMITTED);

    private static final String CONFLICT_MESSAGE = "这张盘点单刚被其他人处理过，请刷新后重试";

    private final StocktakeRepository stocktakeRepository;
    private final StocktakeItemRepository itemRepository;
    private final ChemicalBatchRepository batchRepository;
    private final ChemicalRepository chemicalRepository;
    private final StockLedgerRepository ledgerRepository;

    public StocktakeService(StocktakeRepository stocktakeRepository,
                            StocktakeItemRepository itemRepository,
                            ChemicalBatchRepository batchRepository,
                            ChemicalRepository chemicalRepository,
                            StockLedgerRepository ledgerRepository) {
        this.stocktakeRepository = stocktakeRepository;
        this.itemRepository = itemRepository;
        this.batchRepository = batchRepository;
        this.chemicalRepository = chemicalRepository;
        this.ledgerRepository = ledgerRepository;
    }

    /** 盘点单 + 明细。详情接口一次返回，理由同申请详情 */
    public record Detail(Stocktake stocktake, List<StocktakeItem> items) {
    }

    /**
     * 建单：给选中的试剂（或全部有库存的批次）各生成一条明细，账面量在此刻快照。
     *
     * <b>为什么要挡住「同一批次已在未完成盘点单里」：</b>两张单各持一份 bookQuantity，
     * 先确认的那张一旦调账，后一张的条件更新必然失败。与其让人盘完几十条才发现，
     * 不如建单时就说清楚。条件更新仍然保留——「未完成」到「确认」之间库存还可能被改。
     */
    @Transactional
    public Stocktake create(StocktakeCreateRequest request, String operator) {
        List<ChemicalBatch> batches = (request.chemicalIds() == null || request.chemicalIds().isEmpty())
                ? batchRepository.findAllWithStock()
                : batchRepository.findAllWithStockByChemicalIds(request.chemicalIds());

        if (batches.isEmpty()) {
            throw new BusinessException("没有可盘点的库存：所选试剂当下没有可用量");
        }

        List<Long> batchIds = batches.stream().map(ChemicalBatch::getId).toList();
        List<Long> occupied = itemRepository.findBatchIdsInOpenStocktakes(batchIds, OPEN_STATUSES);
        if (!occupied.isEmpty()) {
            String batchNos = batches.stream()
                    .filter(b -> occupied.contains(b.getId()))
                    .map(ChemicalBatch::getBatchNo)
                    .collect(Collectors.joining("、"));
            throw new BusinessException("以下批次已在未完成的盘点单里，请先完成或撤销那张单：" + batchNos);
        }

        Stocktake stocktake;
        try {
            // 先 flush 再写明细：单号撞唯一索引时这里就抛出来，此时还没有写入任何明细
            stocktake = stocktakeRepository.saveAndFlush(Stocktake.builder()
                    .stocktakeNo(nextStocktakeNo())
                    .status(StocktakeStatus.DRAFT)
                    .remark(request.remark())
                    .createdBy(operator)
                    .build());
        } catch (DataIntegrityViolationException e) {
            // 单号是 32 位随机序号，多实例部署时仍可能撞上唯一索引。
            // 这是可重试的冲突，不是服务端故障，返回业务错误而不是 500
            throw new BusinessException("盘点单号生成冲突，请重新提交");
        }

        itemRepository.saveAll(batches.stream()
                .map(b -> StocktakeItem.builder()
                        .stocktakeId(stocktake.getId())
                        .batchId(b.getId())
                        .chemicalId(b.getChemicalId())
                        .batchNo(b.getBatchNo())
                        .bookQuantity(b.getAvailableQuantity())
                        .build())
                .toList());

        log.info("建盘点单 {}，共 {} 个批次，创建人 {}", stocktake.getStocktakeNo(), batches.size(), operator);
        return stocktake;
    }

    /**
     * 录入一条实盘量。只有 DRAFT 状态可以改。
     *
     * 差异在这里就算出来存下（而不是等到提交）：列表页要能实时看到「数到多少了、差多少」，
     * 让盘点人在录入过程中就能发现异常，而不是提交后才发现有个 0 打错了位置。
     */
    @Transactional
    public StocktakeItem recordActual(String stocktakeNo, Long itemId,
                                      StocktakeItemRequest request) {
        Stocktake stocktake = findByNo(stocktakeNo);
        if (stocktake.getStatus() != StocktakeStatus.DRAFT) {
            throw new BusinessException("只有草稿状态可以录入实盘量，当前状态 " + stocktake.getStatus());
        }

        StocktakeItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("盘点明细不存在: id=" + itemId));
        if (!item.getStocktakeId().equals(stocktake.getId())) {
            throw new BusinessException("明细 " + itemId + " 不属于盘点单 " + stocktakeNo);
        }

        // DTO 上已有 @DecimalMin，这里再兜一次：Service 也会被测试和后台任务直接调用，
        // 负数一旦进台账，对账会永久不平，且很难判断是录入错误还是业务异常
        if (request.actualQuantity().signum() < 0) {
            throw new BusinessException("实盘量不能为负数");
        }

        item.setActualQuantity(request.actualQuantity());
        item.setDiffQuantity(request.actualQuantity().subtract(item.getBookQuantity()));
        item.setNote(request.note());
        log.debug("盘点 {} 批次 {} 录入实盘 {}（账面 {}）", stocktakeNo, item.getBatchNo(),
                request.actualQuantity(), item.getBookQuantity());
        return itemRepository.save(item);
    }

    /**
     * 提交：要求每个批次都录了实盘量。
     *
     * 漏录一条就提交，那条会被当成「差异 0」还是「未盘点」？两种处理都会误导人，
     * 所以在入口直接拒绝，并列出还没数的批次号——盘点人照着清单补就行。
     */
    @Transactional
    public Stocktake submit(String stocktakeNo, String operator) {
        Stocktake stocktake = findByNo(stocktakeNo);
        requireTransition(stocktake, StocktakeStatus.SUBMITTED);

        List<StocktakeItem> items = itemRepository.findByStocktakeIdOrderByIdAsc(stocktake.getId());
        List<String> missing = items.stream()
                .filter(i -> i.getActualQuantity() == null)
                .map(StocktakeItem::getBatchNo)
                .toList();
        if (!missing.isEmpty()) {
            throw new BusinessException("还有 " + missing.size() + " 个批次未录入实盘量："
                    + String.join("、", missing));
        }

        // 重算一遍差异：DRAFT 期间的录入走的是另一个事务，这里以库里的实盘量为准，
        // 不信任任何先前算好挂在内存里的值
        items.forEach(i -> i.setDiffQuantity(i.getActualQuantity().subtract(i.getBookQuantity())));

        stocktake.setStatus(StocktakeStatus.SUBMITTED);
        stocktake.setSubmittedBy(operator);
        stocktake.setSubmittedAt(LocalDateTime.now());
        saveWithOptimisticCheck(stocktake);

        log.info("提交盘点单 {}，{} 个批次，提交人 {}", stocktakeNo, items.size(), operator);
        return stocktake;
    }

    /**
     * 确认：把差异写进台账。
     *
     * 逐条用 {@link ChemicalBatchRepository#adjustTo} 做条件更新，条件里带着建单时的账面量。
     * 任何一条返回 0（说明盘点期间这批库存被出库/入库/报废改过）就抛异常，
     * 整个事务回滚——整张单要么全调、要么全不调。
     *
     * <b>为什么差异写 ADJUST 流水而不是直接改余额：</b>台账的语义是「只增不改」。
     * 盘亏 -20 mL 和「这条记录被改成 280」是两件不同的事：前者能追溯，后者抹掉了历史。
     * 而且写流水之后，对账接口能立刻把这个批次重新对平。
     */
    @Transactional
    public Stocktake confirm(String stocktakeNo, String operator) {
        Stocktake stocktake = findByNo(stocktakeNo);
        requireTransition(stocktake, StocktakeStatus.CONFIRMED);

        List<StocktakeItem> items = itemRepository.findByStocktakeIdOrderByIdAsc(stocktake.getId());
        int adjusted = 0;
        for (StocktakeItem item : items) {
            BigDecimal diff = item.getActualQuantity().subtract(item.getBookQuantity());

            // 差异为 0 也要走这一步条件更新。这里踩过一个真实的坑：
            // 最初「无差异就 continue」，结果零差异条目根本不校验基准——
            // 建单时 2980，盘点期间报废了 10（变 2970），实盘照样录 2980（差异 0），
            // 确认静默通过：盘点单说盘过 2980，库里是 2970，对账还「平」（2970 = 2970）。
            // 也就是说这条路径上盘点什么都没验证，而它恰恰是最常见的路径（多数批次本来就对得上）。
            // 现在的语义是：**每一条都验证基准没变，只有差异为 0 才不写流水**。
            int affected = batchRepository.adjustTo(
                    item.getBatchId(), item.getBookQuantity(), item.getActualQuantity());
            if (affected == 0) {
                throw new BusinessException("批次 " + item.getBatchNo()
                        + " 的账面量在盘点期间发生了变化（建单时 " + item.getBookQuantity()
                        + "），这张盘点单已失效，请撤销后重新盘点");
            }
            if (diff.signum() == 0) {
                // 无差异不写流水：写一条 quantity=0 的记录只会污染台账，让「有变动」这个信号变弱
                continue;
            }

            ledgerRepository.save(StockLedger.builder()
                    .batchId(item.getBatchId())
                    .chemicalId(item.getChemicalId())
                    .changeType("ADJUST")
                    .quantity(diff)
                    .balanceAfter(item.getActualQuantity())
                    .refType("STOCKTAKE")
                    .refId(stocktake.getId())
                    .operator(operator)
                    .build());
            adjusted++;
        }

        stocktake.setStatus(StocktakeStatus.CONFIRMED);
        stocktake.setConfirmedBy(operator);
        stocktake.setConfirmedAt(LocalDateTime.now());
        saveWithOptimisticCheck(stocktake);

        log.info("确认盘点单 {}，调账 {} 个批次，确认人 {}", stocktakeNo, adjusted, operator);
        return stocktake;
    }

    /** 撤销：不调账，只把单子作废。已确认的不能撤销（差异已经进台账了） */
    @Transactional
    public Stocktake cancel(String stocktakeNo, String operator) {
        Stocktake stocktake = findByNo(stocktakeNo);
        requireTransition(stocktake, StocktakeStatus.CANCELLED);
        stocktake.setStatus(StocktakeStatus.CANCELLED);
        saveWithOptimisticCheck(stocktake);
        log.info("撤销盘点单 {}，操作人 {}", stocktakeNo, operator);
        return stocktake;
    }

    public Detail detail(String stocktakeNo) {
        Stocktake stocktake = findByNo(stocktakeNo);
        return new Detail(stocktake,
                itemRepository.findByStocktakeIdOrderByIdAsc(stocktake.getId()));
    }

    /** 详情视图。Controller 只关心「给我这张单的完整样子」，不必自己拼两步 */
    public StocktakeResponse view(String stocktakeNo) {
        return toResponse(detail(stocktakeNo));
    }

    /** 组装对外视图：试剂名与单位按需批量补，不逐条查（N+1） */
    public StocktakeResponse toResponse(Detail detail) {
        List<StocktakeItem> items = detail.items();
        Map<Long, Chemical> chemicals = items.isEmpty()
                ? Map.of()
                : chemicalRepository.findAllById(items.stream()
                        .map(StocktakeItem::getChemicalId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Chemical::getId, Function.identity()));

        return StocktakeResponse.from(detail.stocktake(), items.stream()
                .map(i -> {
                    Chemical c = chemicals.get(i.getChemicalId());
                    return StocktakeItemResponse.from(i,
                            c == null ? null : c.getName(),
                            c == null ? null : c.getUnit());
                })
                .toList());
    }

    @Transactional(readOnly = true)
    public Page<Stocktake> page(StocktakeStatus status, Pageable pageable) {
        Specification<Stocktake> spec = (root, query, cb) -> status == null
                ? cb.conjunction()
                : cb.equal(root.get("status"), status);
        return stocktakeRepository.findAll(spec, pageable);
    }

    private Stocktake findByNo(String stocktakeNo) {
        return stocktakeRepository.findByStocktakeNo(stocktakeNo)
                .orElseThrow(() -> new ResourceNotFoundException("盘点单不存在: " + stocktakeNo));
    }

    private void requireTransition(Stocktake stocktake, StocktakeStatus target) {
        if (!stocktake.getStatus().canTransitionTo(target)) {
            throw new BusinessException("当前状态 " + stocktake.getStatus() + " 不能变更为 " + target
                    + "，允许的下一步是 " + stocktake.getStatus().allowedTargets());
        }
    }

    /**
     * 保存并翻译乐观锁冲突。用 saveAndFlush 而不是 save：后者要到事务提交时才发 UPDATE，
     * 那时候已经出了方法，catch 接不住。理由同 {@code RequisitionService}。
     */
    private void saveWithOptimisticCheck(Stocktake stocktake) {
        try {
            stocktakeRepository.saveAndFlush(stocktake);
        } catch (OptimisticLockingFailureException e) {
            log.warn("盘点单乐观锁冲突 {}", stocktake.getStocktakeNo());
            throw new BusinessException(CONFLICT_MESSAGE);
        }
    }

    /**
     * 单号：规则同申请单，见 {@link DocumentNoGenerator}。
     *
     * 不用「当天流水号 +1」，是因为那要查一次当天最大值，又是一处并发点。
     * 唯一性最终由唯一索引兜底，{@link #create} 会把冲突翻译成 409 而不是 500。
     */
    private String nextStocktakeNo() {
        return DocumentNoGenerator.next("ST");
    }
}
