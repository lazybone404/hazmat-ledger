package com.lazybone.hazmat.service;

import com.lazybone.hazmat.common.BusinessException;
import com.lazybone.hazmat.common.ResourceNotFoundException;
import com.lazybone.hazmat.dto.RequisitionRequest;
import com.lazybone.hazmat.dto.ReviewRequest;
import com.lazybone.hazmat.entity.Chemical;
import com.lazybone.hazmat.entity.Requisition;
import com.lazybone.hazmat.entity.RequisitionLog;
import com.lazybone.hazmat.entity.RequisitionStatus;
import com.lazybone.hazmat.repository.ChemicalRepository;
import com.lazybone.hazmat.repository.RequisitionLogRepository;
import com.lazybone.hazmat.repository.RequisitionRepository;
import io.micrometer.core.instrument.MeterRegistry;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 领用申请的流程：提交 → 审批 → 出库 → 核销，外加撤回。
 *
 * 这个类里值得讲的是**状态流转怎么防并发**。审批是典型的「先读后写」：
 * 必须先读出申请看它是不是 PENDING，才知道能不能批。而这中间的时间窗里，
 * 另一个审批人可能已经批了。两种做法：
 *
 * <ul>
 *   <li><b>条件更新（CAS）</b>：{@code UPDATE ... SET status='APPROVED' WHERE id=? AND status='PENDING'}，
 *       受影响行数 0 就说明状态已经变了。不需要版本号字段。</li>
 *   <li><b>版本号乐观锁</b>：读的时候拿到 version，写回时 Hibernate 自动加上
 *       {@code AND version=?}，不匹配就抛异常。</li>
 * </ul>
 *
 * 这里用了后者。理由不是它更好，而是它**顺带保护了这次更新里的其它字段**
 * （审批人、审批意见、审批时间）：CAS 只盯状态一个字段，如果两个请求都通过了
 * 状态检查，后写的那个会把前一个的审批意见覆盖掉，而版本号会把这种情况一并拦下。
 * 如果只改状态、不带别的字段，条件更新更省一个字段。
 *
 * <b>双人复核又给这个选择加了一条理由。</b>高危试剂要两位不同审批人依次通过
 * （见 {@link RequisitionStatus#PENDING_SECOND}）。两个人同时点通过时，
 * 乐观锁会让其中一方写失败——但**失败的那一方不能就这么算了**，他的审批本来就该算数
 * （正好是缺的第二票）。所以 {@link #review} 在版本冲突时**换一个新事务重读重判**，
 * 而不是把冲突直接报给用户。这一点和扣库存不同：扣库存冲突时那一方是可以放弃的
 * （货被别人领走了，他本来也领不到），审批冲突时那一方恰恰是流程需要的人。
 */
@Slf4j
@Service
public class RequisitionService {

    /** 审批遇到版本冲突时最多重试几次。冲突意味着「有人比我先动」，重读到最新状态再判一次就行 */
    private static final int REVIEW_MAX_ATTEMPTS = 3;

    private static final String REVIEW_CONFLICT_MESSAGE = "这条申请刚被其他人处理过，请刷新后重试";

    private final RequisitionRepository requisitionRepository;
    private final RequisitionLogRepository logRepository;
    private final ChemicalRepository chemicalRepository;
    private final StockService stockService;
    private final MeterRegistry meterRegistry;
    private final DocumentNoService documentNoService;

    /**
     * 审批的事务边界由模板显式控制，不再用 {@code @Transactional}。原因见 {@link #review}：
     * 版本冲突要换一个新事务重来，而同一个事务里抛过乐观锁异常之后持久化上下文已经不可用。
     */
    private final TransactionTemplate reviewTx;
    /** 建单也要在单号冲突时换新事务重试，见 {@link #submit} */
    private final TransactionTemplate submitTx;

    public RequisitionService(RequisitionRepository requisitionRepository,
                              RequisitionLogRepository logRepository,
                              ChemicalRepository chemicalRepository,
                              StockService stockService,
                              DocumentNoService documentNoService,
                              PlatformTransactionManager transactionManager,
                              MeterRegistry meterRegistry) {
        this.requisitionRepository = requisitionRepository;
        this.logRepository = logRepository;
        this.chemicalRepository = chemicalRepository;
        this.stockService = stockService;
        this.documentNoService = documentNoService;
        this.reviewTx = new TransactionTemplate(transactionManager);
        this.submitTx = new TransactionTemplate(transactionManager);
        this.meterRegistry = meterRegistry;
    }

    /** 申请详情：申请本身 + 完整流转时间线。台账要能回答「这步谁做的、什么理由」 */
    public record Detail(Requisition requisition, List<RequisitionLog> timeline) {
    }

    /**
     * 提交申请。{@code applicant} 由 Controller 从登录凭证取，不从请求体读——
     * Service 保留参数而不是直接读 SecurityContext，是为了让它能在后台任务、
     * 并发测试里被直接调用，不依赖线程上的认证上下文。
     */
    public Requisition submit(String applicant, RequisitionRequest request) {
        try {
            return submitTx.execute(status -> submitOnce(applicant, request));
        } catch (DataIntegrityViolationException e) {
            // 号段缓存可能已经落后于数据库（库被恢复、有人手工插过单号）。
            // 丢弃本地段后换一个新事务重试一次，不要直接让用户重提。
            log.info("申请单号撞唯一索引，丢弃本地号段后重试");
            documentNoService.invalidate("REQ");
            try {
                return submitTx.execute(status -> submitOnce(applicant, request));
            } catch (DataIntegrityViolationException retry) {
                throw new BusinessException("申请单号生成冲突，请重新提交");
            }
        }
    }

    private Requisition submitOnce(String applicant, RequisitionRequest request) {
        if (!chemicalRepository.existsById(request.chemicalId())) {
            throw new ResourceNotFoundException("试剂不存在: id=" + request.chemicalId());
        }
        Requisition r = requisitionRepository.saveAndFlush(Requisition.builder()
                .reqNo(nextReqNo())
                .applicant(applicant)
                .chemicalId(request.chemicalId())
                .quantity(request.quantity())
                .purpose(request.purpose())
                .status(RequisitionStatus.PENDING)
                .build());

        // 创建也记一条，时间线才是完整的。fromStatus 留空，见实体上的说明
        record(r.getId(), null, RequisitionStatus.PENDING, applicant, "提交领用申请");
        return r;
    }

    /**
     * 审批。通过 / 驳回走同一条路径，只差目标状态。
     *
     * <b>双人复核的规则（{@code hazardLevel=高} 的试剂）：</b>
     * <ul>
     *   <li>第一位审批人通过只把申请推到 {@link RequisitionStatus#PENDING_SECOND}，
     *       还要**另一位**审批人再通过一次才到 APPROVED；普通试剂仍然一步到 APPROVED。</li>
     *   <li><b>同一个人不能凑两次数。</b>第二次通过时比对 {@code firstReviewer}，是同一个人就拒。
     *       这一条是「双人」的实质：只统计「批过几次」的实现，同一个人点两次就凑够了。</li>
     *   <li>任一人驳回即驳回，不看人数（早已通过的第一人再改口驳回也认，那是「终止」不是「计数」）。</li>
     * </ul>
     *
     * <b>为什么这个方法不用 {@code @Transactional}，而是自己开事务：</b>并发在这里是**正常业务**，
     * 不是故障。甲、乙几乎同时点通过时，两人都读到 PENDING，其中一方写回时版本号不匹配——
     * 但这一方恰恰是流程还缺的那个人，他的票不能丢。所以冲突要**换一个新事务重读重判**：
     * 重读会看到 PENDING_SECOND，于是他的这一票正好成为第二票。
     * 同一个事务里乐观锁异常之后持久化上下文已经不可用，重试必须在新事务里做，
     * 事务边界因此由模板显式控制。
     * （这里也没有自我调用任何 {@code @Transactional} 方法——那样会被 Spring 代理绕过，
     * 注解静默失效，本项目在别处专门记过这个坑。）
     */
    public Requisition review(String reqNo, String reviewer, ReviewRequest request) {
        for (int attempt = 1; ; attempt++) {
            try {
                return reviewTx.execute(status -> doReview(reqNo, reviewer, request));
            } catch (OptimisticLockingFailureException e) {
                log.info("审批撞上版本冲突，重读重判（第 {} 次）reqNo={}", attempt, reqNo);
                if (attempt >= REVIEW_MAX_ATTEMPTS) {
                    throw new BusinessException(REVIEW_CONFLICT_MESSAGE);
                }
            }
        }
    }

    /**
     * 一次审批尝试。事务由 {@link #review} 开，这里刻意不带 {@code @Transactional}。
     *
     * 两个决定状态去向的东西各归各位：**目标是哪个状态**由状态机判合法性
     * （{@code canTransitionTo}），**这一票算不算数**由这里判（是不是同一个人）。
     * 前者是状态到状态的规则，后者取决于「上一个人是谁」，是数据不是状态，所以放不进枚举。
     */
    private Requisition doReview(String reqNo, String reviewer, ReviewRequest request) {
        Requisition r = findByReqNo(reqNo);
        RequisitionStatus from = r.getStatus();

        // 自批自领在危化品管理里是明确的红线：审批的意义就是第二双眼睛。
        // 放在状态判断之前，驳回也不允许本人来做——那同样是在替自己了结流程。
        if (reviewer.equals(r.getApplicant())) {
            throw new BusinessException("申请人不能审批自己的申请");
        }

        RequisitionStatus target;
        if (!request.approved()) {
            target = RequisitionStatus.REJECTED;                    // 任一人驳回即驳回
        } else if (from == RequisitionStatus.PENDING && needsDualReview(r)) {
            target = RequisitionStatus.PENDING_SECOND;              // 高危：这一票只是第一票
        } else {
            target = RequisitionStatus.APPROVED;                    // 普通试剂，或高危的第二票
        }

        if (!from.canTransitionTo(target)) {
            throw new BusinessException("当前状态 " + from + " 不能变更为 " + target
                    + "，允许的下一步是 " + from.allowedTargets());
        }

        // 「双人」的实质在这一行：不是数够两次，而是第二次必须换一个人
        if (target == RequisitionStatus.APPROVED && from == RequisitionStatus.PENDING_SECOND
                && reviewer.equals(r.getFirstReviewer())) {
            throw new BusinessException("双人复核要求两位不同的审批人：「" + reviewer
                    + "」已经批过第一票，不能自己凑第二票");
        }

        r.setStatus(target);
        if (target == RequisitionStatus.PENDING_SECOND) {
            // 第一票落在 firstReviewer 上。reviewer 留给「最终拍板的人」，
            // 因为走到这里还没有拍板——申请还在等第二个人
            r.setFirstReviewer(reviewer);
        } else {
            r.setReviewer(reviewer);
            r.setReviewComment(request.comment());
            r.setReviewedAt(LocalDateTime.now());
        }
        // 这里不走 saveWithOptimisticCheck：冲突要留给外层重试，不能被就地翻译成业务异常
        requisitionRepository.saveAndFlush(r);

        record(r.getId(), from, target, reviewer,
                request.comment() == null ? "" : request.comment());
        return r;
    }

    /**
     * 这份申请要不要双人复核：看试剂档案的危险等级。
     *
     * 顺便说明一处看起来像漏洞的地方：{@code hazardLevel} 是可改的档案字段，
     * 若在第一票之后被改成「中」，第二票仍然要人——因为申请已经落在 PENDING_SECOND 上，
     * **状态一旦落下就以状态为准**。已签的字不该因为档案被编辑而悄悄作废。
     */
    private boolean needsDualReview(Requisition r) {
        return chemicalRepository.findById(r.getChemicalId())
                .map(Chemical::isHighHazard)
                .orElse(false);
    }

    /** 出库。真正的扣减在 StockService，这里只管状态和记录 */
    @Transactional
    public Requisition issue(String reqNo, String operator) {
        Requisition r = findByReqNo(reqNo);
        RequisitionStatus from = r.getStatus();
        if (!from.canTransitionTo(RequisitionStatus.ISSUED)) {
            throw new BusinessException("当前状态 " + from + " 不能出库，只有 APPROVED 可以");
        }

        StockService.IssueOutcome outcome =
                stockService.issue(r.getChemicalId(), r.getQuantity(), r.getId(), operator);
        if (outcome.isEmpty()) {
            throw new BusinessException("库存不足，无法出库");
        }

        r.setStatus(RequisitionStatus.ISSUED);
        r.setIssuedQuantity(outcome.issuedQuantity());
        r.setIssuedBatchId(outcome.allocations().isEmpty() ? null : outcome.allocations().get(0).batchId());
        r.setIssuedAt(LocalDateTime.now());
        saveWithOptimisticCheck(r, "这条申请刚被其他人处理过，请刷新后重试");

        String detail = outcome.allocations().stream()
                .map(a -> a.batchNo() + " " + a.quantity())
                .collect(Collectors.joining("；"));
        String comment = outcome.issuedQuantity().compareTo(r.getQuantity()) < 0
                ? "库存不足，部分出库 " + outcome.issuedQuantity() + "（申请 " + r.getQuantity() + "）；" + detail
                : "出库 " + outcome.issuedQuantity() + "；" + detail;
        record(r.getId(), from, RequisitionStatus.ISSUED, operator, comment);
        return r;
    }

    /** 核销。实验用完了确认销账，流程的最后一环 */
    @Transactional
    public Requisition close(String reqNo, String operator) {
        Requisition r = findByReqNo(reqNo);
        RequisitionStatus from = r.getStatus();
        if (!from.canTransitionTo(RequisitionStatus.CLOSED)) {
            throw new BusinessException("当前状态 " + from + " 不能核销，只有 ISSUED 可以");
        }
        r.setStatus(RequisitionStatus.CLOSED);
        r.setClosedAt(LocalDateTime.now());
        saveWithOptimisticCheck(r, "这条申请刚被其他人处理过，请刷新后重试");
        record(r.getId(), from, RequisitionStatus.CLOSED, operator, "已核销");
        return r;
    }

    /**
     * 撤回。**只允许申请人本人**。
     *
     * 这条权限判断放在 Service 而不是靠接口层拦，因为它是业务规则不是访问控制：
     * 换个入口（定时任务、批处理）照样要守这条。
     */
    @Transactional
    public Requisition cancel(String reqNo, String operator) {
        Requisition r = findByReqNo(reqNo);
        RequisitionStatus from = r.getStatus();
        if (!operator.equals(r.getApplicant())) {
            throw new BusinessException("只有申请人本人可以撤回，当前操作人: " + operator);
        }
        if (!from.canTransitionTo(RequisitionStatus.CANCELLED)) {
            throw new BusinessException("当前状态 " + from + " 不能撤回");
        }
        r.setStatus(RequisitionStatus.CANCELLED);
        saveWithOptimisticCheck(r, "这条申请刚被其他人处理过，请刷新后重试");
        record(r.getId(), from, RequisitionStatus.CANCELLED, operator, "申请人撤回");
        return r;
    }

    @Transactional(readOnly = true)
    public Detail detail(String reqNo) {
        Requisition r = findByReqNo(reqNo);
        return new Detail(r, logRepository.findByRequisitionIdOrderByCreatedAtAscIdAsc(r.getId()));
    }

    /**
     * 分页查询申请单。
     *
     * 申请人是**必填**的：接口层保证普通用户只能传自己，审批员与管理员可以传别人。
     * 这里不做权限判断——Service 不认识「当前是谁」，权限属于接口层的事
     * （见 {@code RequisitionController}）。若在这里读 SecurityContext，
     * 并发测试和后台任务就没法复用了。
     *
     * 时间范围按「自然日」解释：{@code createdTo} 当天 23:59:59 的申请也算在内。
     * 直接拿 {@code to} 当上界的话，用户选「到今天」会漏掉今天的全部数据。
     */
    @Transactional(readOnly = true)
    public Page<Requisition> page(String applicant, RequisitionStatus status,
                                  LocalDate createdFrom, LocalDate createdTo,
                                  Pageable pageable) {
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw new BusinessException("开始日期不能晚于结束日期");
        }
        Specification<Requisition> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("applicant"), applicant));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (createdFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"),
                        createdFrom.atStartOfDay()));
            }
            if (createdTo != null) {
                predicates.add(cb.lessThan(root.get("createdAt"),
                        createdTo.plusDays(1).atStartOfDay()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return requisitionRepository.findAll(spec, pageable);
    }

    private Requisition findByReqNo(String reqNo) {
        return requisitionRepository.findByReqNo(reqNo)
                .orElseThrow(() -> new ResourceNotFoundException("申请不存在: " + reqNo));
    }

    /**
     * 保存并把乐观锁冲突翻译成业务异常。
     *
     * 用 saveAndFlush 而不是 save：save 只是把实体挂进持久化上下文，真正的 UPDATE
     * 要到事务提交时才发出去，那时候已经出了这个方法，try/catch 接不住。
     * 必须在这里就把 SQL 发出去，异常才拦得到。
     */
    private void saveWithOptimisticCheck(Requisition r, String message) {
        try {
            requisitionRepository.saveAndFlush(r);
        } catch (OptimisticLockingFailureException e) {
            log.warn("乐观锁冲突 reqNo={}", r.getReqNo());
            throw new BusinessException(message);
        }
    }

    /**
     * 写流转日志，并顺带计一次「状态迁移」。
     *
     * 计数点是这里而不是各个业务方法：所有状态变化都必须经过 {@code record}，
     * 单独在 submit/review/issue 里各加一次，迟早会漏掉新增的动作（撤回就是这么来的）。
     * {@code from} 为空表示新建申请，用 {@code NEW} 作起点，避免 tag 出现空串。
     *
     * 已知边界：计数发生在事务提交前，若随后事务回滚，指标仍会 +1。
     * 指标用于看趋势而非对账，接受这点偏差，换取埋点集中在一处。
     */
    private void record(Long requisitionId, RequisitionStatus from, RequisitionStatus to,
                        String operator, String comment) {
        meterRegistry.counter("requisition.transition",
                        "from", from == null ? "NEW" : from.name(),
                        "to", to.name())
                .increment();
        logRepository.save(RequisitionLog.builder()
                .requisitionId(requisitionId)
                .fromStatus(from)
                .toStatus(to)
                .operator(operator)
                .comment(comment)
                .build());
    }

    /**
     * 申请单号：见 {@link DocumentNoService}。
     *
     * 号段从数据库分配，进程内自增；段用完再申请下一段。单号只用于人眼识别和
     * 口头沟通，不要求连续。唯一性由号段和单号唯一索引共同保证，
     * {@link #submit} 在撞号时会丢弃本地段并重试一次。
     */
    private String nextReqNo() {
        return documentNoService.next("REQ");
    }
}
