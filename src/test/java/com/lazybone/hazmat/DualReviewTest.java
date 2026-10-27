package com.lazybone.hazmat;

import com.lazybone.hazmat.common.BusinessException;
import com.lazybone.hazmat.dto.ChemicalRequest;
import com.lazybone.hazmat.dto.InboundRequest;
import com.lazybone.hazmat.dto.RequisitionRequest;
import com.lazybone.hazmat.dto.ReviewRequest;
import com.lazybone.hazmat.entity.Chemical;
import com.lazybone.hazmat.entity.Requisition;
import com.lazybone.hazmat.entity.RequisitionLog;
import com.lazybone.hazmat.entity.RequisitionStatus;
import com.lazybone.hazmat.repository.ChemicalBatchRepository;
import com.lazybone.hazmat.repository.ChemicalRepository;
import com.lazybone.hazmat.repository.RequisitionLogRepository;
import com.lazybone.hazmat.repository.RequisitionRepository;
import com.lazybone.hazmat.repository.StockLedgerRepository;
import com.lazybone.hazmat.service.ChemicalService;
import com.lazybone.hazmat.service.RequisitionService;
import com.lazybone.hazmat.service.StockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 双人复核测试。
 *
 * 业务依据是《高等学校实验室安全规范》（教育部，2023-02-08 施行）第二十五条：
 * 剧毒化学品执行「五双」——双人验收、双人保管、<b>双人发货</b>、双把锁、双本账。
 * 落到这个项目里就是：{@code hazardLevel=高} 的试剂，领用申请要**两个不同的人**
 * 各自审批通过才算通过。
 *
 * <b>这个类里真正要守住的那条线是「同一个人不能凑两次数」，不是「批了两次」。</b>
 * 只数次数的实现在这里会全绿，但业务上等于没做双人——所以
 * {@link #samePersonCannotApproveTwice()} 才是这个类的核心断言。
 *
 * 和 {@code StockConcurrencyTest} 一样不标 {@code @Transactional}：并发那条测试要的是
 * 「多个事务真的同时打同一行」，标上事务就变成「一个事务里调两次」，测不出东西。
 */
@SpringBootTest
class DualReviewTest {

    private static final String REVIEWER_A = "审批人甲";
    private static final String REVIEWER_B = "审批人乙";
    private static final String APPLICANT = "张三";

    @Autowired private ChemicalService chemicalService;
    @Autowired private StockService stockService;
    @Autowired private RequisitionService requisitionService;
    @Autowired private ChemicalRepository chemicalRepository;
    @Autowired private ChemicalBatchRepository batchRepository;
    @Autowired private RequisitionRepository requisitionRepository;
    @Autowired private RequisitionLogRepository requisitionLogRepository;
    @Autowired private StockLedgerRepository ledgerRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    /** 高危试剂：走双人复核 */
    private Long highHazardId;
    /** 普通试剂：一个人批就够，用来守住「老路径没被改坏」 */
    private Long normalId;

    @BeforeEach
    void setUp() {
        requisitionLogRepository.deleteAll();
        requisitionRepository.deleteAll();
        ledgerRepository.deleteAll();
        batchRepository.deleteAll();
        chemicalRepository.deleteAll();

        highHazardId = chemicalService.create(new ChemicalRequest(
                "CAS-DUAL-H", "氰化钾", "有毒", "高", "mL", "AR 100mL", null, null)).getId();
        stockService.inbound(highHazardId,
                new InboundRequest("B-DUAL-H", new BigDecimal("1000"), "剧毒柜 A-1", LocalDate.now().plusYears(1)),
                "测试");

        normalId = chemicalService.create(new ChemicalRequest(
                "CAS-DUAL-N", "氯化钠", "一般", "中", "mL", "AR 500mL", null, null)).getId();
        stockService.inbound(normalId,
                new InboundRequest("B-DUAL-N", new BigDecimal("1000"), "普通柜 B-1", LocalDate.now().plusYears(1)),
                "测试");
    }

    private Requisition submit(Long chemicalId) {
        return requisitionService.submit(APPLICANT, new RequisitionRequest(
                chemicalId, new BigDecimal("10"), "合成实验用"));
    }

    private Requisition approve(String reqNo, String reviewer) {
        return requisitionService.review(reqNo, reviewer, new ReviewRequest(true, reviewer + " 同意"));
    }

    private Requisition reload(String reqNo) {
        return requisitionRepository.findByReqNo(reqNo).orElseThrow();
    }

    /** 时间线里的审批事件（提交那条的 toStatus 是 PENDING，被滤掉） */
    private List<RequisitionLog> reviewEvents(String reqNo) {
        return requisitionLogRepository
                .findByRequisitionIdOrderByCreatedAtAscIdAsc(reload(reqNo).getId()).stream()
                .filter(l -> l.getToStatus() == RequisitionStatus.PENDING_SECOND
                        || l.getToStatus() == RequisitionStatus.APPROVED
                        || l.getToStatus() == RequisitionStatus.REJECTED)
                .toList();
    }

    @Test
    @DisplayName("高危试剂：一个人批不算完，要另一个不同的人再批一次才算通过")
    void highHazardNeedsTwoDifferentApprovers() {
        Requisition r = submit(highHazardId);

        Requisition first = approve(r.getReqNo(), REVIEWER_A);
        assertEquals(RequisitionStatus.PENDING_SECOND, first.getStatus(),
                "高危试剂第一个人批完只能到 PENDING_SECOND，不能直接 APPROVED");
        assertEquals(REVIEWER_A, first.getFirstReviewer(), "第一票要记在第一审批人上");
        assertNull(first.getReviewer(), "还没拍板，reviewer 不该有值——那是最终决定人");

        Requisition second = approve(r.getReqNo(), REVIEWER_B);
        assertEquals(RequisitionStatus.APPROVED, second.getStatus(), "第二个人批完才通过");
        assertEquals(REVIEWER_B, second.getReviewer());
        assertEquals(REVIEWER_A, second.getFirstReviewer(), "第一票的签名不能被第二票覆盖");

        List<RequisitionLog> events = reviewEvents(r.getReqNo());
        assertEquals(2, events.size(), "两次审批各留一条记录，一条都不能少");
        // 第一人 / 第二人不靠新字段区分，而是状态机本身：PENDING→PENDING_SECOND 就是第一票
        assertEquals(RequisitionStatus.PENDING, events.get(0).getFromStatus());
        assertEquals(RequisitionStatus.PENDING_SECOND, events.get(0).getToStatus());
        assertEquals(REVIEWER_A, events.get(0).getOperator());
        assertEquals(RequisitionStatus.PENDING_SECOND, events.get(1).getFromStatus());
        assertEquals(RequisitionStatus.APPROVED, events.get(1).getToStatus());
        assertEquals(REVIEWER_B, events.get(1).getOperator());
    }

    @Test
    @DisplayName("同一个人点两次不算双人：第二次被拒，状态停在 PENDING_SECOND")
    void samePersonCannotApproveTwice() {
        Requisition r = submit(highHazardId);
        approve(r.getReqNo(), REVIEWER_A);

        BusinessException e = assertThrows(BusinessException.class,
                () -> approve(r.getReqNo(), REVIEWER_A),
                "同一个审批人批第二次必须被拒——否则「双人」就是一句话术");
        assertTrue(e.getMessage().contains("不同的审批人"), "实际: " + e.getMessage());

        assertEquals(RequisitionStatus.PENDING_SECOND, reload(r.getReqNo()).getStatus(),
                "被拒的那一票不能推进状态");
        assertEquals(1, reviewEvents(r.getReqNo()).size(), "被拒的那一票不能留审批记录");

        // 被拒之后流程还是好的：换个人来照样能批完
        assertEquals(RequisitionStatus.APPROVED, approve(r.getReqNo(), REVIEWER_B).getStatus());
    }

    @Test
    @DisplayName("任一人驳回即驳回：第一人之后的第二人驳回，以及第一人直接驳回")
    void eitherApproverCanReject() {
        Requisition a = submit(highHazardId);
        approve(a.getReqNo(), REVIEWER_A);
        Requisition rejected = requisitionService.review(a.getReqNo(), REVIEWER_B,
                new ReviewRequest(false, "用量与实验规模不符"));
        assertEquals(RequisitionStatus.REJECTED, rejected.getStatus(),
                "第二个人驳回就够了，不需要两个人都驳回");
        assertEquals(REVIEWER_B, rejected.getReviewer());
        assertEquals(REVIEWER_A, rejected.getFirstReviewer(), "驳回也不该抹掉第一票的签名");

        // 反过来：第一位审批人直接驳回，同样一步到位
        Requisition b = submit(highHazardId);
        Requisition rejectedAtFirst = requisitionService.review(b.getReqNo(), REVIEWER_A,
                new ReviewRequest(false, "用途写得不清楚"));
        assertEquals(RequisitionStatus.REJECTED, rejectedAtFirst.getStatus());
        assertEquals(1, reviewEvents(b.getReqNo()).size());
    }

    @Test
    @DisplayName("普通试剂行为不变：一个人批就直接通过（改动没伤到老路径）")
    void normalHazardStillNeedsOnlyOneApprover() {
        Requisition r = submit(normalId);

        Requisition approved = approve(r.getReqNo(), REVIEWER_A);

        assertEquals(RequisitionStatus.APPROVED, approved.getStatus(), "非高危试剂一个人批就该通过");
        assertNull(approved.getFirstReviewer(), "没走双人复核，就不该有第一审批人");
        assertEquals(1, reviewEvents(r.getReqNo()).size());
    }

    @Test
    @DisplayName("只批了一票的高危申请出不了库：中间态挡得住，库存一点没动")
    void pendingSecondCannotBeIssued() {
        Requisition r = submit(highHazardId);
        approve(r.getReqNo(), REVIEWER_A);

        BusinessException e = assertThrows(BusinessException.class,
                () -> requisitionService.issue(r.getReqNo(), "王库管"));
        assertTrue(e.getMessage().contains("不能出库"), "实际: " + e.getMessage());
        assertEquals(0, batchRepository.sumAvailable(highHazardId).compareTo(new BigDecimal("1000")),
                "只批了一票就出库，等于绕过了第二个人");
    }

    /**
     * 并发点：甲、乙几乎同时点通过。
     *
     * 期望不是「一个成功一个失败」，而是**两张票都要记上**——这正是双人复核要的结果，
     * 也是乐观锁不能用「冲突就报错」了事的地方（见 {@code RequisitionService#review} 的注释）。
     *
     * <b>并发窗口是被一把真锁撑开的，不是靠两个线程抢时间。</b>一开始这里只用了发令枪
     * （CountDownLatch 同时放两个线程），实测两个线程会一前一后各跑完整个 review，
     * 一次版本冲突都没撞上——那样测到的其实是一个顺序执行，
     * 「冲突之后重读重判」那段新代码等于零覆盖。改法是用**试剂档案行**的写锁当起跑线：
     * <ol>
     *   <li>主线程开一个外层事务，把试剂那一行真改一下（不改就不会发出 UPDATE）并占住写锁；</li>
     *   <li>两个审批线程各自跑 review：都会在「读完申请、去查试剂档案」这一步停下来
     *       ——H2 的普通 SELECT 会等未提交的写锁（实测：被挡住的那一方耗时正好等于等待时长）；</li>
     *   <li>于是两边都已经读到了申请的旧版本 PENDING，却都还没写回；主线程提交放行之后，
     *       两个人才一起去 UPDATE 同一行——版本冲突<b>必然</b>发生一次。
     *       谁先谁后交给数据库，测试不去假设。</li>
     * </ol>
     * 断言三条：恰好一个人成为第一人（返回 PENDING_SECOND）、另一个人成为第二人
     * （返回 APPROVED）；最终状态 APPROVED 且审批事件恰好两条；两条事件的 operator
     * 是甲和乙各一次——没有人被算两次。
     */
    @Test
    @DisplayName("并发双人复核：两个不同审批人同时点通过，两票都算数且各算一次")
    void concurrentTwoApproversBothCount() throws Exception {
        Requisition r = submit(highHazardId);
        String reqNo = r.getReqNo();

        Map<String, RequisitionStatus> returned = new ConcurrentHashMap<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch launched = new CountDownLatch(2);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Chemical chemical = chemicalRepository.findById(highHazardId).orElseThrow();
            String originalSpec = chemical.getSpec();
            chemical.setSpec("起跑线探针");                    // 真改一个字段，行锁才拿得到
            chemicalRepository.saveAndFlush(chemical);

            for (String who : List.of(REVIEWER_A, REVIEWER_B)) {
                pool.submit(() -> {
                    launched.countDown();
                    try {
                        returned.put(who, approve(reqNo, who).getStatus());
                    } catch (Throwable t) {
                        unexpected.compareAndSet(null, t);
                    }
                });
            }
            await(launched);
            sleepQuietly(300);                               // 两个线程读完申请、卡在试剂行锁上的时间

            chemical.setSpec(originalSpec);                  // 探测痕迹抹掉，提交后试剂档案还是原样
            chemicalRepository.saveAndFlush(chemical);
        });

        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "两个线程没在 60 秒内跑完，可能卡在锁上");

        if (unexpected.get() != null) {
            fail("两个不同的审批人同时点通过，不该有任何一方失败：" + unexpected.get());
        }

        assertEquals(2, returned.size());
        assertEquals(1, returned.values().stream()
                        .filter(st -> st == RequisitionStatus.PENDING_SECOND).count(),
                "必须恰好一个人成为第一人，实际: " + returned);
        assertEquals(1, returned.values().stream()
                        .filter(st -> st == RequisitionStatus.APPROVED).count(),
                "另一个人应当成为第二人并把它批完，实际: " + returned);

        Requisition finalState = reload(reqNo);
        assertEquals(RequisitionStatus.APPROVED, finalState.getStatus());
        assertEquals(2, reviewEvents(reqNo).size(), "两票都要留在时间线上，一票都不能丢");

        List<String> operators = reviewEvents(reqNo).stream()
                .map(RequisitionLog::getOperator).toList();
        assertEquals(Set.of(REVIEWER_A, REVIEWER_B), Set.copyOf(operators),
                "两次审批必须是两个不同的人，实际: " + operators);
        assertEquals(2, operators.stream().collect(Collectors.toSet()).size());
        assertTrue(operators.contains(finalState.getFirstReviewer())
                        && operators.contains(finalState.getReviewer())
                        && !finalState.getFirstReviewer().equals(finalState.getReviewer()),
                "最终字段应当一个指向第一人、一个指向第二人：first="
                        + finalState.getFirstReviewer() + " reviewer=" + finalState.getReviewer());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等锁超时，要暴露的是流程问题而不是把测试挂住");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
