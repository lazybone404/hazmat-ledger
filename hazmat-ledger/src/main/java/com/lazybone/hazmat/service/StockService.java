package com.lazybone.hazmat.service;

import com.lazybone.hazmat.common.BusinessException;
import com.lazybone.hazmat.common.PageResponse;
import com.lazybone.hazmat.common.ResourceNotFoundException;
import com.lazybone.hazmat.dto.BatchResponse;
import com.lazybone.hazmat.dto.ExpiringBatchResponse;
import com.lazybone.hazmat.dto.InboundRequest;
import com.lazybone.hazmat.dto.ReconcileView;
import com.lazybone.hazmat.dto.ScrapRequest;
import com.lazybone.hazmat.dto.ScrapResult;
import com.lazybone.hazmat.dto.StockView;
import com.lazybone.hazmat.entity.Chemical;
import com.lazybone.hazmat.entity.ChemicalBatch;
import com.lazybone.hazmat.entity.StockLedger;
import com.lazybone.hazmat.repository.ChemicalBatchRepository;
import com.lazybone.hazmat.repository.ChemicalRepository;
import com.lazybone.hazmat.repository.StockLedgerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 库存服务：入库和出库。
 *
 * 这个类是这个项目里最该被追问的地方，两个点：
 *
 * <b>一、出库为什么不用 Redis。</b>这里的真实并发量是「一个实验室几个人同时领用」，
 * 一天几十次，但正确性要求一样高。上 Redis 的代价是多一个中间件、多一处一致性问题
 * （Redis 扣了数据库没扣怎么办）、多一套运维；而不加缓存时，扣减与台账写在同一个事务里，
 * 一致性由数据库本身保证。**并发量不到那个级别，条件更新就是更划算的答案**，压测确认过
 * 读路径随数据量退化的只有关键词搜索与报表聚合，与缓存无关（见 docs/开发记录.md）。
 *
 * <b>二、balanceAfter 怎么保证准。</b>{@code deduct} 是条件更新，成功后该行被本事务
 * 加锁直到提交，所以紧接着读到的余额就是真实值，不会中途被改。细节见 {@link #issue}。
 */
@Slf4j
@Service
public class StockService {

    /** 临期判定：30 天内到期就预警 */
    private static final long EXPIRING_SOON_DAYS = 30;

    private final ChemicalRepository chemicalRepository;
    private final ChemicalBatchRepository batchRepository;
    private final StockLedgerRepository ledgerRepository;
    private final ChemicalService chemicalService;

    public StockService(ChemicalRepository chemicalRepository,
                        ChemicalBatchRepository batchRepository,
                        StockLedgerRepository ledgerRepository,
                        ChemicalService chemicalService) {
        this.chemicalRepository = chemicalRepository;
        this.batchRepository = batchRepository;
        this.ledgerRepository = ledgerRepository;
        this.chemicalService = chemicalService;
    }

    /** 一次出库的结果：实际发出去多少、从哪几个批次发的 */
    public record IssueOutcome(BigDecimal issuedQuantity, List<Allocation> allocations) {

        public record Allocation(Long batchId, String batchNo, BigDecimal quantity) {
        }

        public boolean isEmpty() {
            return issuedQuantity.signum() <= 0;
        }
    }

    /** 入库：给某试剂新建一个批次。等价于「入库日期 = 今天」的那个重载 */
    @Transactional
    public BatchResponse inbound(Long chemicalId, InboundRequest request, String operator) {
        return doInbound(chemicalId, request, operator, LocalDate.now());
    }

    /**
     * 带入库日期的入库 —— 给**历史数据回填**用（种子数据、补录以前收的货）。
     *
     * <b>为什么校验要锚在「入库日期」上，而不是锚在 {@code now()}。</b>
     * 「不许收已过期的货」这条规则管的是**新收进来的货**；它管不到
     * 「以前收过、后来才过期」的批次——那种批次本来就该躺在库里，
     * 由出库时的 FEFO 过滤和库存视图的预警去处理。
     *
     * 原来的写法把校验锚死在 {@code now()}，于是种子数据里那几个
     * 「入库时还没过期、现在过期了」的演示批次，在真实日期走过去之后
     * 就被自己的校验拦下，应用启动直接失败（2026-10-10 实际发生）。
     * 根因不是「日期写死」，是**用「现在入库」的入口去造「历史入库」的数据**：
     * 校验锚在 now()，而历史数据的锚应该是入库那一刻。
     *
     * 注意视图仍然锚在今天：{@link BatchResponse} 里的临期天数是「距今天还有几天」，
     * 跟入库日期无关。两个锚各管各的，别混。
     */
    @Transactional
    public BatchResponse inbound(Long chemicalId, InboundRequest request, String operator, LocalDate inboundDate) {
        return doInbound(chemicalId, request, operator, inboundDate);
    }

    /**
     * 真正的实现。
     *
     * <b>为什么拆成私有方法而不是让三参版直接调四参版：</b>同类内部调用不走 Spring 代理，
     * 四参版上的 {@code @Transactional} 会静默失效——同一个类里两个 public 的
     * {@code @Transactional} 方法互相调用，是被问到时最容易答错的一处。
     * 拆出私有实现之后，两个入口各自带事务，谁调谁都不影响。
     */
    private BatchResponse doInbound(Long chemicalId, InboundRequest request, String operator,
                                    LocalDate inboundDate) {
        Chemical chemical = chemicalRepository.findById(chemicalId)
                .orElseThrow(() -> new ResourceNotFoundException("试剂不存在: id=" + chemicalId));

        // 已过期的东西不该被收进来。这条挡在入口，比让它在库里躺着再靠出库时过滤更干净：
        // 收进来之后它就成了「台账上有一笔但不能用」的悬空数据，还得额外解释。
        // 判据是「相对入库那一天过期了吗」，不是「相对今天过期了吗」。
        if (request.expireDate().isBefore(inboundDate)) {
            throw new BusinessException("不能入库已过期的批次，有效期填的是 " + request.expireDate()
                    + "，入库日期 " + inboundDate);
        }

        // 查重走 (chemical_id, batch_no) 唯一索引做点查，成本与该试剂的批次数无关。
        // 不能改成「查出全部批次再 anyMatch」：那是 O(n)，同一试剂批次过千后
        // 入库吞吐随批次数线性下降（压测实测，见 docs/开发记录.md）。
        if (batchRepository.existsByChemicalIdAndBatchNo(chemicalId, request.batchNo())) {
            throw new BusinessException("批次号已存在: " + request.batchNo());
        }

        ChemicalBatch batch;
        try {
            batch = batchRepository.save(ChemicalBatch.builder()
                    .chemicalId(chemicalId)
                    .batchNo(request.batchNo())
                    .initialQuantity(request.quantity())
                    .availableQuantity(request.quantity())
                    .location(request.location())
                    .expireDate(request.expireDate())
                    // 新入库的批次一定未开封，可用截止日就是包装有效期。
                    // 开封后由 openBatch 提前它，之后所有查询只认 usableUntil
                    .usableUntil(request.expireDate())
                    .build());
        } catch (DataIntegrityViolationException e) {
            // 并发下两个请求可以都通过上面的检查，由唯一索引兜底。
            // 转成业务错误，否则调用方拿到的是 500——批次号重复是可纠正的输入问题。
            throw new BusinessException("批次号已存在: " + request.batchNo());
        }

        ledgerRepository.save(StockLedger.builder()
                .batchId(batch.getId())
                .chemicalId(chemicalId)
                .changeType("IN")
                .quantity(request.quantity())
                .balanceAfter(request.quantity())
                .refType("INBOUND")
                .refId(batch.getId())
                .operator(operator)
                .build());

        boolean backfill = inboundDate.isBefore(LocalDate.now());
        log.info("{} {} {} {}，批次 {}（入库日期 {}）", backfill ? "回填入库" : "入库",
                chemical.getName(), request.quantity(), chemical.getUnit(),
                request.batchNo(), inboundDate);
        return BatchResponse.from(batch, LocalDate.now());
    }

    /**
     * 出库。按**先到期先出**（FEFO）挑批次，逐个用条件更新扣减。
     *
     * 为什么按效期而不是按入库时间（FIFO）：实验室里先进的那批可能有效期更长，
     * 后进的那批反而快过期。台账的目标是「不让东西过期浪费」，所以排序依据必须是效期。
     * 这一条不是从代码里能看出来的，得懂业务才会这么写，也容易被问。
     *
     * 允许部分出库：库存只够一部分时，发出去已有部分，返回值里的 issuedQuantity
     * 会小于申请量。把「够不够」当异常抛出去反而要求调用方先查一次，多一次往返。
     */
    @Transactional
    public IssueOutcome issue(Long chemicalId, BigDecimal required, Long refId, String operator) {
        BigDecimal remaining = required;
        List<IssueOutcome.Allocation> allocations = new ArrayList<>();

        // 传 today 进去把**已过期**的批次挡在外面。这一步不能省：
        // 按效期升序的初衷是「先消耗快到期的」，但过期批次恰好排在第一位，
        // 不加过滤就变成优先发报废品——冒烟测试打时间线时才发现。
        for (ChemicalBatch batch : batchRepository.findAvailableBatches(chemicalId, LocalDate.now())) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal take = batch.getAvailableQuantity().min(remaining);

            // 条件更新：只有这一瞬间余量还够，才会扣成功。
            // 返回 0 说明这批次被别人抢在前面扣掉了，跳过它继续看下一批，
            // 而不是让整个请求失败——库存还有的话，用户不该因为这一批没了就领不到。
            int affected = batchRepository.deduct(batch.getId(), take);
            if (affected == 0) {
                log.info("批次 {} 余量已变，跳过", batch.getBatchNo());
                continue;
            }

            // 扣成功之后这一行被本事务锁住，读到的是确定的真实余额。
            // 这里必须重新查，不能用「先前读到的值 - take」算：先前那个值是
            // 扣减之前读的，中间可能已经被别的事务改过，算出来的余额是错的，
            // 而流水一旦记错，对账就永远对不上。
            BigDecimal balanceAfter = batchRepository.currentQuantity(batch.getId());

            ledgerRepository.save(StockLedger.builder()
                    .batchId(batch.getId())
                    .chemicalId(chemicalId)
                    .changeType("OUT")
                    .quantity(take.negate())
                    .balanceAfter(balanceAfter)
                    .refType("REQUISITION")
                    .refId(refId)
                    .operator(operator)
                    .build());

            allocations.add(new IssueOutcome.Allocation(batch.getId(), batch.getBatchNo(), take));
            remaining = remaining.subtract(take);
        }

        BigDecimal issued = required.subtract(remaining);
        log.info("出库 chemicalId={} 申请 {} 实发 {} 批次 {}", chemicalId, required, issued,
                allocations.stream().map(IssueOutcome.Allocation::batchNo).toList());
        return new IssueOutcome(issued, allocations);
    }

    /**
     * 开封：记录开封时间与操作人，并按档案里的「开封后可用天数」提前可用截止日。
     *
     * <b>为什么开封会改变可用期。</b>批次上的 {@code expireDate} 是供应商按「未开封」
     * 标注的包装有效期。试剂开封后稳定性重新起算：甲醇吸湿、过氧化氢分解放氧、
     * 乙醚生成过氧化物。只看包装有效期，就会把一瓶开封三个月、早已变质的试剂
     * 当成「还有半年」继续发出去。
     *
     * <b>为什么只提前、不延后。</b>可用期取 min(expireDate, 开封日 + postOpenDays)。
     * 开封后不可能比包装有效期更久——那等于用开封重新赋予了保质期。
     *
     * <b>为什么重复开封直接拒绝，而不是覆盖时间。</b>覆盖会把可用期往回推，
     * 等于「换一瓶新的」；如果这瓶其实一直开着，重开就把变质期悄悄抹掉了。
     *
     * <b>为什么不写 {@code StockLedger}。</b>开封不改数量。往台账里塞 quantity=0 的记录
     * 会稀释「这一笔有变动」的信号，流水页也会出现大段与数量无关的行。
     * 开封是批次的状态事件，记录在 openedAt/openedBy 上，审计同样回答得出
     * 「谁在什么时候开的封」。
     *
     * 试剂没有配置 postOpenDays 时只留痕、不改可用期——固体、惰性物质不需要这一套。
     */
    @Transactional
    public BatchResponse openBatch(Long chemicalId, Long batchId, String operator) {
        ChemicalBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("批次不存在: id=" + batchId));
        if (!batch.getChemicalId().equals(chemicalId)) {
            throw new BusinessException("批次 " + batch.getBatchNo() + " 不属于试剂 " + chemicalId);
        }
        if (batch.isOpened()) {
            throw new BusinessException("批次 " + batch.getBatchNo() + " 已于 "
                    + batch.getOpenedAt() + " 开封，不能重复开封");
        }
        // 空批次开封没有业务含义：没有东西可开，也不该借这个动作去改一个已清空批次的可用期
        if (batch.getAvailableQuantity().signum() <= 0) {
            throw new BusinessException("批次 " + batch.getBatchNo() + " 已无可用库存，不需要开封");
        }

        LocalDateTime openedAt = LocalDateTime.now();

        Integer postOpenDays = chemicalRepository.findById(chemicalId)
                .map(Chemical::getPostOpenDays)
                .orElse(null);
        LocalDate limit = null;
        if (postOpenDays != null) {
            LocalDate candidate = openedAt.toLocalDate().plusDays(postOpenDays);
            LocalDate current = batch.getUsableUntil() == null
                    ? batch.getExpireDate() : batch.getUsableUntil();
            if (candidate.isBefore(current)) {
                limit = candidate;
            }
        }

        // 上面的 isOpened() 只挡住了「已经开过」的串行情况。两个请求同时进来时都能读到
        // 「未开封」，所以真正的判断必须交给数据库：WHERE 里带 opened_at is null，
        // 返回 0 就说明被另一个请求抢先开了。和扣库存一样，判断与写入不能分两步。
        int affected = limit == null
                ? batchRepository.markOpened(batchId, openedAt, operator)
                : batchRepository.markOpenedShortening(batchId, openedAt, operator, limit);
        if (affected == 0) {
            throw new BusinessException("批次 " + batch.getBatchNo() + " 已被并发开封，不能重复开封");
        }

        // 批量更新不经过持久化上下文（方法上标了 clearAutomatically），这里重新读出真实结果
        ChemicalBatch opened = batchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("批次不存在: id=" + batchId));
        log.info("开封 chemicalId={} 批次 {}，操作人 {}，可用截止日 {}（包装有效期 {}）",
                chemicalId, opened.getBatchNo(), operator, opened.getUsableUntil(), opened.getExpireDate());
        return BatchResponse.from(opened, LocalDate.now());
    }

    /**
     * 报废：把批次里不可用的量扣掉，并写一条台账流水。
     *
     * 过期批次在出库时被 FEFO 过滤掉，若没有报废出口，这些量会永远挂在批次表上：
     * 库存视图持续提示「未处理」，台账总量也降不下来。报废是过期、破损、实验废弃
     * 这几类库存的唯一合法出口。
     *
     * 和出库复用同一条条件更新：判断「够不够扣」与扣减在同一条 SQL 里，
     * 所以并发报废、报废与出库同时发生时都不会扣成负数。
     *
     * 允许报废未过期批次（破损、变质、实验废弃），但必须写原因——
     * 台账要能回答「为什么少了」。校验批次归属，防止拿错批次跨试剂扣减。
     */
    @Transactional
    public ScrapResult scrap(Long chemicalId, Long batchId, ScrapRequest request, String operator) {
        ChemicalBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("批次不存在: id=" + batchId));
        if (!batch.getChemicalId().equals(chemicalId)) {
            throw new BusinessException("批次 " + batch.getBatchNo() + " 不属于试剂 " + chemicalId);
        }

        int affected = batchRepository.deduct(batchId, request.quantity());
        if (affected == 0) {
            throw new BusinessException("报废量超过批次当前可用量，批次 " + batch.getBatchNo());
        }

        // 与出库一致：扣减成功后该行被本事务锁住，重新读出的才是确定余额。
        BigDecimal balanceAfter = batchRepository.currentQuantity(batchId);

        ledgerRepository.save(StockLedger.builder()
                .batchId(batchId)
                .chemicalId(chemicalId)
                .changeType("SCRAP")
                .quantity(request.quantity().negate())
                .balanceAfter(balanceAfter)
                .refType("SCRAP")
                .refId(batchId)
                .operator(operator)
                .build());

        log.info("报废 chemicalId={} 批次 {} {}，原因：{}，剩余 {}", chemicalId,
                batch.getBatchNo(), request.quantity(), request.reason(), balanceAfter);
        return new ScrapResult(batchId, batch.getBatchNo(), request.quantity(),
                balanceAfter, request.reason(), operator);
    }

    /**
     * 某试剂当前的库存视图：批次明细 + 可用总量 + 预警。
     *
     * 档案这一层走缓存（{@link ChemicalService#findById}），批次与余额**不走**——
     * 库存页每次刷新都要读档案，而余额一旦陈旧就是错账，两者在这里必须分开。
     * 缓存返回的是重建出来的游离对象，本方法只读取它的字段，不会写回。
     */
    @Transactional(readOnly = true)
    public StockView stockView(Long chemicalId) {
        Chemical chemical = chemicalService.findById(chemicalId);
        LocalDate today = LocalDate.now();

        List<ChemicalBatch> batches = batchRepository.findByChemicalIdOrderByExpireDateAsc(chemicalId);
        List<String> alerts = new ArrayList<>();
        for (ChemicalBatch b : batches) {
            if (b.getAvailableQuantity().signum() <= 0) {
                continue;
            }
            // 预警按「实际可用截止日」算：开封后它可能早于包装有效期，
            // 按包装有效期算会把一瓶开封后已经不能用的试剂显示成「还有半年」
            LocalDate usableUntil = b.getUsableUntil() == null ? b.getExpireDate() : b.getUsableUntil();
            long days = usableUntil.toEpochDay() - today.toEpochDay();
            // 「开封后到期」与「包装过期」要分开说：前者多半是这瓶用得太慢，
            // 后者是根本没动过。两句都写报废，但原因不同，处理决定也不同
            boolean shortenedByOpening = b.isOpened() && usableUntil.isBefore(b.getExpireDate());
            String reason = shortenedByOpening
                    ? "开封后可用期（包装有效期 " + b.getExpireDate() + "）"
                    : "有效期";
            if (days < 0) {
                alerts.add("批次 " + b.getBatchNo() + " " + (shortenedByOpening ? "开封后已超过可用期 " : "已过期 ")
                        + (-days) + " 天，仍有 " + b.getAvailableQuantity() + " "
                        + chemical.getUnit() + " 未处理");
            } else if (days <= EXPIRING_SOON_DAYS) {
                alerts.add("批次 " + b.getBatchNo() + " " + reason + "还剩 " + days + " 天");
            }
        }

        BigDecimal total = batchRepository.sumUsable(chemicalId, today);
        if (total.signum() <= 0) {
            alerts.add("该试剂当前无可用库存（注意：已过期的批次不算可用）");
        }

        return new StockView(chemicalId, chemical.getName(), chemical.getUnit(), total,
                batches.stream().map(b -> BatchResponse.from(b, today)).toList(), alerts);
    }

    /**
     * 某试剂的出入库流水分页。
     *
     * 先确认试剂存在：不存在时返回空列表会被误读成「这个试剂没有任何流水」，
     * 而调用方实际传错了 id。404 比空页更容易定位问题。
     */
    @Transactional(readOnly = true)
    public Page<StockLedger> ledger(Long chemicalId, Pageable pageable) {
        if (!chemicalRepository.existsById(chemicalId)) {
            throw new ResourceNotFoundException("试剂不存在: id=" + chemicalId);
        }
        return ledgerRepository.findByChemicalId(chemicalId, pageable);
    }

    /**
     * 效期预警：所有仍有库存、且将在 {@code withinDays} 天内到期（含已过期）的批次。
     *
     * 与 {@link #stockView} 的 alerts 区别：那是「一个试剂」的视图，
     * 这里是「全实验室要处理的清单」，所以跨试剂、按到期时间升序、可分页。
     *
     * 化学名称用一次批量查询补上，不逐条 findById——那会变成 N+1。
     */
    @Transactional(readOnly = true)
    public PageResponse<ExpiringBatchResponse> expiring(int withinDays, Pageable pageable) {
        LocalDate today = LocalDate.now();
        Page<ChemicalBatch> batches = batchRepository.findExpiring(today.plusDays(withinDays), pageable);
        if (batches.isEmpty()) {
            return PageResponse.from(batches, b -> ExpiringBatchResponse.from(b, null, today));
        }

        Map<Long, Chemical> chemicals = chemicalRepository
                .findAllById(batches.getContent().stream().map(ChemicalBatch::getChemicalId).toList())
                .stream()
                .collect(Collectors.toMap(Chemical::getId, Function.identity()));

        return PageResponse.from(batches,
                b -> ExpiringBatchResponse.from(b, chemicals.get(b.getChemicalId()), today));
    }

    /**
     * 对账：把「批次表上的余额」和「流水记的余额」摆在一起比，两层都看。
     *
     * <b>为什么需要这个接口。</b>台账里存了两种口径的同一个数字：批次表的
     * {@code availableQuantity} 是快照，流水里的 {@code balanceAfter} 是逐笔记账的结果。
     * 只要写路径有一条漏写流水、或者某次更新绕过了流水直接改余额，两者就会漂移，
     * 而这种漂移在界面上完全看不出来——库存视图读的是批次表，流水页读的是流水，
     * 各自都「正常」。对账是唯一能把不一致暴露出来的地方。
     *
     * <b>为什么逐批次和汇总量都要比。</b>只看总量，两个批次一多一少刚好抵消就查不出来；
     * 只看逐批次，又发现不了「总量口径」上的偏差（比如汇总时算错范围）。
     * 两个层次一起看，成本只是各一次查询。
     *
     * <b>为什么总量用 {@code sumAvailable} 而不是 {@code sumUsable}。</b>
     * 对账比的是账实是否一致，与效期无关：过期批次的余额仍然计在批次表上，
     * 也计在流水里，两边都含它。用可用量（排除过期）去比流水累计必然不平。
     *
     * 注意汇总量是「全部流水 quantity 之和」，而逐批次比的是「最后一笔的 balanceAfter」——
     * 两种算法同一个数，互为交叉验证。任一不平都判不一致。
     */
    @Transactional(readOnly = true)
    public ReconcileView reconcile(Long chemicalId) {
        Chemical chemical = chemicalRepository.findById(chemicalId)
                .orElseThrow(() -> new ResourceNotFoundException("试剂不存在: id=" + chemicalId));

        // 该试剂的全部批次，含已经清零的：一条流水都不该丢，清零的批次同样要对上
        List<ChemicalBatch> batches = batchRepository.findByChemicalIdOrderByExpireDateAsc(chemicalId);

        // 每批次最后一笔流水的余额。用 LinkedHashMap 保持批次表的顺序，
        // 输出顺序稳定，前端不用二次排序
        Map<Long, BigDecimal> latestBalance = new LinkedHashMap<>();
        ledgerRepository.findLatestPerBatch(chemicalId)
                .forEach(l -> latestBalance.put(l.getBatchId(), l.getBalanceAfter()));

        BigDecimal totalAvailable = batches.stream()
                .map(ChemicalBatch::getAvailableQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalLedger = ledgerRepository.sumQuantityByChemical(chemicalId);
        if (totalLedger == null) {
            totalLedger = BigDecimal.ZERO;
        }

        List<ReconcileView.BatchBalance> rows = new ArrayList<>(batches.size());
        boolean allConsistent = true;
        for (ChemicalBatch b : batches) {
            BigDecimal ledgerBalance = latestBalance.get(b.getId());
            // 没有流水 = 这个批次来路不明（入库必写流水），判不一致而不是「跳过」
            boolean ok = ledgerBalance != null
                    && ledgerBalance.compareTo(b.getAvailableQuantity()) == 0;
            allConsistent &= ok;
            rows.add(new ReconcileView.BatchBalance(
                    b.getId(), b.getBatchNo(), b.getLocation(),
                    b.getAvailableQuantity(), ledgerBalance, ok));
        }

        boolean consistent = allConsistent && totalLedger.compareTo(totalAvailable) == 0;
        if (!consistent) {
            // 不一致要留痕：这是数据完整性问题，不是普通业务错误，需要被运维看到
            log.warn("对账不平 chemicalId={} 批次合计 {} 流水累计 {}", chemicalId, totalAvailable, totalLedger);
        }
        return new ReconcileView(chemicalId, chemical.getName(), chemical.getUnit(),
                consistent, totalAvailable, totalLedger, rows);
    }
}
