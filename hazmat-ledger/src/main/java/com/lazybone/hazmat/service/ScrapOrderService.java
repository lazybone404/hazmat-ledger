package com.lazybone.hazmat.service;

import com.lazybone.hazmat.common.BusinessException;
import com.lazybone.hazmat.common.ConflictException;
import com.lazybone.hazmat.common.ResourceNotFoundException;
import com.lazybone.hazmat.dto.ScrapOrderRequest;
import com.lazybone.hazmat.dto.ScrapReviewRequest;
import com.lazybone.hazmat.entity.ChemicalBatch;
import com.lazybone.hazmat.entity.ScrapOrder;
import com.lazybone.hazmat.entity.ScrapOrderStatus;
import com.lazybone.hazmat.repository.ChemicalBatchRepository;
import com.lazybone.hazmat.repository.ScrapOrderRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 报废审批单：申请 → 审批 → 扣减。
 *
 * <p>这个类最值得看的一点是**审批失败怎么办**。创建时校验「不超过当前可用量」，
 * 但两张待审单可以在同一时刻都通过校验；第二张审批时，余量已经被第一张扣走。
 * 如果这时直接抛异常回滚，单据会停在 PENDING——库存已经不够，重试还是失败，
 * 申请人撤销又可能没注意到，单据就永远悬着。
 *
 * <p>所以扣减失败不抛异常，而是把单据标成 REJECTED 并写清原因，让事务正常提交；
 * 提交之后再向调用方返回 409。单据状态是事实，库存不够也是事实，两者都该留下。
 */
@Slf4j
@Service
public class ScrapOrderService {

    private static final String AUTO_REJECT_COMMENT = "审批时可用量不足，已自动驳回";

    private final ScrapOrderRepository scrapOrderRepository;
    private final ChemicalBatchRepository batchRepository;
    private final StockService stockService;
    private final DocumentNoService documentNoService;
    private final TransactionTemplate createTx;
    private final TransactionTemplate reviewTx;

    public ScrapOrderService(ScrapOrderRepository scrapOrderRepository,
                             ChemicalBatchRepository batchRepository,
                             StockService stockService,
                             DocumentNoService documentNoService,
                             PlatformTransactionManager transactionManager) {
        this.scrapOrderRepository = scrapOrderRepository;
        this.batchRepository = batchRepository;
        this.stockService = stockService;
        this.documentNoService = documentNoService;
        this.createTx = new TransactionTemplate(transactionManager);
        this.reviewTx = new TransactionTemplate(transactionManager);
    }

    /**
     * 审批结果。{@code stockShort} 为 true 表示单据已被自动驳回、事务已提交，
     * 调用方应返回 409 而不是 200。
     */
    public record ReviewOutcome(ScrapOrder order, boolean stockShort, String message) {
    }

    /**
     * 提交报废申请。只校验「提交这一刻」的余量；真正的守门人仍是审批时的条件扣减。
     */
    public ScrapOrder create(ScrapOrderRequest request, String applicant) {
        try {
            return createTx.execute(status -> createOnce(request, applicant));
        } catch (DataIntegrityViolationException e) {
            // 号段缓存可能落后于数据库（库被恢复、有人手工插过单号），
            // 与领用申请同一套处理：丢弃本地段，换新事务重试一次。
            log.info("报废单号撞唯一索引，丢弃本地号段后重试");
            documentNoService.invalidate("SCRAP");
            try {
                return createTx.execute(status -> createOnce(request, applicant));
            } catch (DataIntegrityViolationException retry) {
                throw new BusinessException("报废单号生成冲突，请重新提交");
            }
        }
    }

    private ScrapOrder createOnce(ScrapOrderRequest request, String applicant) {
        ChemicalBatch batch = batchRepository.findById(request.batchId())
                .orElseThrow(() -> new ResourceNotFoundException("批次不存在: id=" + request.batchId()));
        if (!batch.getChemicalId().equals(request.chemicalId())) {
            throw new BusinessException("批次 " + batch.getBatchNo()
                    + " 不属于试剂 " + request.chemicalId());
        }
        if (batch.getAvailableQuantity().compareTo(request.quantity()) < 0) {
            throw new BusinessException("报废量 " + request.quantity() + " 超过批次 "
                    + batch.getBatchNo() + " 当前可用量 " + batch.getAvailableQuantity());
        }

        ScrapOrder order = scrapOrderRepository.saveAndFlush(ScrapOrder.builder()
                .scrapNo(documentNoService.next("SCRAP"))
                .chemicalId(request.chemicalId())
                .batchId(request.batchId())
                .batchNo(batch.getBatchNo())
                .quantity(request.quantity())
                .reason(request.reason())
                .status(ScrapOrderStatus.PENDING)
                .applicant(applicant)
                .build());
        log.info("提交报废申请 {} 批次 {} {}，原因：{}", order.getScrapNo(), batch.getBatchNo(),
                request.quantity(), request.reason());
        return order;
    }

    /**
     * 审批。扣减失败时把单据置为 REJECTED 并提交，然后抛 409 告诉调用方原因。
     *
     * <p>为什么用事务模板而不是 {@code @Transactional}：409 必须在事务提交之后才抛，
     * 否则异常会把「自动驳回」这个状态一起回滚掉，单据又回到 PENDING。
     */
    public ReviewOutcome review(String scrapNo, String reviewer, ScrapReviewRequest request) {
        ReviewOutcome outcome;
        try {
            outcome = reviewTx.execute(status -> doReview(scrapNo, reviewer, request));
        } catch (OptimisticLockingFailureException e) {
            log.warn("报废审批撞版本冲突 scrapNo={}", scrapNo);
            throw new BusinessException("这张报废单刚被其他人处理过，请刷新后重试");
        }
        if (outcome == null) {
            throw new IllegalStateException("报废审批没有返回结果: " + scrapNo);
        }
        if (outcome.stockShort()) {
            throw new ConflictException(outcome.message());
        }
        return outcome;
    }

    private ReviewOutcome doReview(String scrapNo, String reviewer, ScrapReviewRequest request) {
        ScrapOrder order = findByScrapNo(scrapNo);
        ScrapOrderStatus from = order.getStatus();

        if (!from.canTransitionTo(request.approved()
                ? ScrapOrderStatus.APPROVED : ScrapOrderStatus.REJECTED)) {
            throw new BusinessException("当前状态 " + from + " 不能审批，允许的下一步是 "
                    + from.allowedTargets());
        }
        // 自批自废与自批自领同一条红线：审批的意义是第二双眼睛。
        if (reviewer.equals(order.getApplicant())) {
            throw new BusinessException("申请人不能审批自己的报废单");
        }

        if (!request.approved()) {
            order.setStatus(ScrapOrderStatus.REJECTED);
            order.setReviewer(reviewer);
            order.setReviewComment(request.comment());
            order.setReviewedAt(LocalDateTime.now());
            scrapOrderRepository.saveAndFlush(order);
            log.info("驳回报废单 {}，审批人 {}", scrapNo, reviewer);
            return new ReviewOutcome(order, false, null);
        }

        StockService.ScrapOutcome scrap = stockService.tryScrap(order.getChemicalId(),
                order.getBatchId(), order.getQuantity(), order.getReason(),
                "SCRAP_ORDER", order.getId(), reviewer);

        if (!scrap.success()) {
            // 关键分支：不能只回滚。单据必须留下「为什么没批成」，
            // 否则它会停在 PENDING，重试仍失败、撤销又容易被忽略。
            order.setStatus(ScrapOrderStatus.REJECTED);
            order.setReviewer(reviewer);
            order.setReviewComment(AUTO_REJECT_COMMENT + "：" + scrap.failureMessage());
            order.setReviewedAt(LocalDateTime.now());
            scrapOrderRepository.saveAndFlush(order);
            log.info("报废单 {} 扣减失败，已自动驳回：{}", scrapNo, scrap.failureMessage());
            return new ReviewOutcome(order, true,
                    "该批次可用量已变化，报废单已自动驳回，请核对库存后重新提交");
        }

        order.setStatus(ScrapOrderStatus.APPROVED);
        order.setReviewer(reviewer);
        order.setReviewComment(request.comment());
        order.setReviewedAt(LocalDateTime.now());
        scrapOrderRepository.saveAndFlush(order);
        log.info("通过报废单 {}，审批人 {}，批次 {} 扣减 {}", scrapNo, reviewer,
                order.getBatchNo(), order.getQuantity());
        return new ReviewOutcome(order, false, null);
    }

    /** 撤销：只允许申请人本人、只在 PENDING。与领用撤回同一条规则 */
    @Transactional
    public ScrapOrder cancel(String scrapNo, String operator) {
        ScrapOrder order = findByScrapNo(scrapNo);
        if (!operator.equals(order.getApplicant())) {
            throw new BusinessException("只有申请人本人可以撤销，当前操作人: " + operator);
        }
        if (!order.getStatus().canTransitionTo(ScrapOrderStatus.CANCELLED)) {
            throw new BusinessException("当前状态 " + order.getStatus() + " 不能撤销");
        }
        order.setStatus(ScrapOrderStatus.CANCELLED);
        saveWithOptimisticCheck(order, "这张报废单刚被其他人处理过，请刷新后重试");
        log.info("撤销报废单 {}，操作人 {}", scrapNo, operator);
        return order;
    }

    @Transactional(readOnly = true)
    public ScrapOrder detail(String scrapNo) {
        return findByScrapNo(scrapNo);
    }

    /**
     * 分页。{@code applicant} 为空表示不按申请人过滤，权限由 Controller 负责——
     * 普通用户会被强制传自己的用户名，审批员与管理员可以看全部。
     */
    @Transactional(readOnly = true)
    public Page<ScrapOrder> page(String applicant, ScrapOrderStatus status, Pageable pageable) {
        Specification<ScrapOrder> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (applicant != null && !applicant.isBlank()) {
                predicates.add(cb.equal(root.get("applicant"), applicant));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return scrapOrderRepository.findAll(spec, pageable);
    }

    private ScrapOrder findByScrapNo(String scrapNo) {
        return scrapOrderRepository.findByScrapNo(scrapNo)
                .orElseThrow(() -> new ResourceNotFoundException("报废单不存在: " + scrapNo));
    }

    /**
     * 保存并把乐观锁冲突翻译成业务异常。
     *
     * <p>必须 saveAndFlush：save 要到事务提交时才发 SQL，异常已经出了方法，catch 接不住。
     */
    private void saveWithOptimisticCheck(ScrapOrder order, String message) {
        try {
            scrapOrderRepository.saveAndFlush(order);
        } catch (OptimisticLockingFailureException e) {
            log.warn("报废单乐观锁冲突 scrapNo={}", order.getScrapNo());
            throw new BusinessException(message);
        }
    }
}
