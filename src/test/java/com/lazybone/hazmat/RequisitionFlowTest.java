package com.lazybone.hazmat;

import com.lazybone.hazmat.common.BusinessException;
import com.lazybone.hazmat.dto.ChemicalRequest;
import com.lazybone.hazmat.dto.InboundRequest;
import com.lazybone.hazmat.dto.RequisitionRequest;
import com.lazybone.hazmat.dto.ReviewRequest;
import com.lazybone.hazmat.entity.Requisition;
import com.lazybone.hazmat.entity.RequisitionStatus;
import com.lazybone.hazmat.entity.StockLedger;
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
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 领用流程测试。
 *
 * 同样**不标 {@code @Transactional}**，但原因和并发测试不同：这里的乐观锁测试
 * 需要「实体在事务外是游离态」这个前提。标了事务之后 {@code findById} 拿到的
 * 是受管实体，改它不会触发版本比对，那条测试就永远绿——**测了个假东西**。
 */
@SpringBootTest
class RequisitionFlowTest {

    @Autowired private ChemicalService chemicalService;
    @Autowired private StockService stockService;
    @Autowired private RequisitionService requisitionService;
    @Autowired private ChemicalRepository chemicalRepository;
    @Autowired private ChemicalBatchRepository batchRepository;
    @Autowired private RequisitionRepository requisitionRepository;
    @Autowired private RequisitionLogRepository requisitionLogRepository;
    @Autowired private StockLedgerRepository ledgerRepository;

    private Long chemicalId;

    @BeforeEach
    void setUp() {
        requisitionLogRepository.deleteAll();
        requisitionRepository.deleteAll();
        ledgerRepository.deleteAll();
        batchRepository.deleteAll();
        chemicalRepository.deleteAll();

        chemicalId = chemicalService.create(new ChemicalRequest(
                "CAS-FLOW-001", "流程测试试剂", "易燃", "中", "mL", "AR 500mL", null, null)).getId();
        stockService.inbound(chemicalId,
                new InboundRequest("B-FLOW-1", new BigDecimal("500"), "测试柜", LocalDate.now().plusYears(1)),
                "测试");
    }

    private Requisition submit(String applicant, String qty) {
        return requisitionService.submit(applicant, new RequisitionRequest(
                chemicalId, new BigDecimal(qty), "合成实验用"));
    }

    @Test
    @DisplayName("走完整条流程，时间线四步齐全且每一步的前后状态对得上")
    void happyPathWalksTheWholeTimeline() {
        Requisition r = submit("张三", "50");
        assertEquals(RequisitionStatus.PENDING, r.getStatus());

        requisitionService.review(r.getReqNo(), "李老师", new ReviewRequest(true, "同意"));
        requisitionService.issue(r.getReqNo(), "王库管");
        Requisition closed = requisitionService.close(r.getReqNo(), "王库管");

        assertEquals(RequisitionStatus.CLOSED, closed.getStatus());

        RequisitionService.Detail detail = requisitionService.detail(r.getReqNo());
        var timeline = detail.timeline();
        assertEquals(4, timeline.size(), "提交 + 审批 + 出库 + 核销，应该是四条");
        assertNull(timeline.get(0).getFromStatus(), "创建那条没有前一个状态");
        assertEquals(RequisitionStatus.PENDING, timeline.get(0).getToStatus());
        assertEquals(RequisitionStatus.PENDING, timeline.get(1).getFromStatus());
        assertEquals(RequisitionStatus.APPROVED, timeline.get(1).getToStatus());
        assertEquals(RequisitionStatus.APPROVED, timeline.get(2).getFromStatus());
        assertEquals(RequisitionStatus.ISSUED, timeline.get(2).getToStatus());
        assertEquals(RequisitionStatus.ISSUED, timeline.get(3).getFromStatus());
        assertEquals(RequisitionStatus.CLOSED, timeline.get(3).getToStatus());
    }

    @Test
    @DisplayName("没审批就出库：状态机拦下，且库存一点没动")
    void issueBeforeApprovalIsRejected() {
        Requisition r = submit("张三", "50");

        BusinessException e = assertThrows(BusinessException.class,
                () -> requisitionService.issue(r.getReqNo(), "王库管"));
        assertTrue(e.getMessage().contains("不能出库"), "实际: " + e.getMessage());
        assertEquals(0, batchRepository.sumAvailable(chemicalId).compareTo(new BigDecimal("500")),
                "被拒绝的请求不该动库存");
    }

    @Test
    @DisplayName("驳回是终态：再想审批通过会被拒")
    void rejectedIsFinal() {
        Requisition r = submit("张三", "50");
        requisitionService.review(r.getReqNo(), "李老师", new ReviewRequest(false, "用途写得不清楚"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> requisitionService.review(r.getReqNo(), "李老师", new ReviewRequest(true, "改主意了")));
        assertTrue(e.getMessage().contains("不能变更"), "实际: " + e.getMessage());
    }

    @Test
    @DisplayName("申请人不能审批自己的申请：即使角色允许也不行")
    void applicantCannotApproveOwnRequest() {
        Requisition r = submit("张三", "50");

        BusinessException e = assertThrows(BusinessException.class,
                () -> requisitionService.review(r.getReqNo(), "张三", new ReviewRequest(true, "自己批")),
                "自批自领必须被拒，否则审批等于没有");
        assertTrue(e.getMessage().contains("不能审批自己的申请"), "实际: " + e.getMessage());
        assertEquals(RequisitionStatus.PENDING,
                requisitionService.detail(r.getReqNo()).requisition().getStatus(),
                "被拒的审批不能推进状态");

        // 驳回同样不行：替自己了结流程也是在绕开第二双眼睛
        assertThrows(BusinessException.class,
                () -> requisitionService.review(r.getReqNo(), "张三", new ReviewRequest(false, "自己做主")));
    }

    @Test
    @DisplayName("撤回只能本人：别人撤不了，本人可以")
    void onlyApplicantCanCancel() {
        Requisition r = submit("张三", "50");

        assertThrows(BusinessException.class, () -> requisitionService.cancel(r.getReqNo(), "李四"));
        assertEquals(RequisitionStatus.PENDING,
                requisitionService.detail(r.getReqNo()).requisition().getStatus(), "被拒后状态不该变");

        Requisition cancelled = requisitionService.cancel(r.getReqNo(), "张三");
        assertEquals(RequisitionStatus.CANCELLED, cancelled.getStatus());
    }

    @Test
    @DisplayName("拿着过期的实体去写：被乐观锁拦下，不覆盖别人的审批意见")
    void staleEntityWriteIsRejectedByOptimisticLock() {
        Requisition r = submit("张三", "50");

        // 模拟「另一个审批人先打开了详情页」：事务外读出来，实体是游离的，version=0
        Requisition stale = requisitionRepository.findByReqNo(r.getReqNo()).orElseThrow();

        // 期间别人已经审批通过（version 变成 1）
        requisitionService.review(r.getReqNo(), "李老师", new ReviewRequest(true, "同意，注意通风"));

        // 老页面提交：带上的是旧版本号，而且想改的是同一个字段
        stale.setReviewComment("覆盖成我的意见");
        assertThrows(OptimisticLockingFailureException.class,
                () -> requisitionRepository.saveAndFlush(stale),
                "版本号不匹配必须被拦下，否则后写的人会悄悄覆盖先写的人的审批意见");

        assertEquals("同意，注意通风",
                requisitionRepository.findByReqNo(r.getReqNo()).orElseThrow().getReviewComment(),
                "先写入的审批意见必须还在");
    }

    @Test
    @DisplayName("出库按先到期先出挑批次，跨批次自动拆分")
    void issuePicksEarliestExpiringBatchFirst() {
        Long other = chemicalService.create(new ChemicalRequest(
                "CAS-FEFO-001", "效期排序试剂", "易燃", "中", "mL", "AR 500mL", null, null)).getId();
        // 先入库的批次效期更晚，后入库的批次效期更早——故意这样造，
        // 用来证明挑批次看的是效期，不是入库先后（FIFO 会挑错）
        Long lateBatch = stockService.inbound(other,
                new InboundRequest("B-LATE", new BigDecimal("100"), "柜 1", LocalDate.now().plusYears(2)),
                "测试").id();
        Long soonBatch = stockService.inbound(other,
                new InboundRequest("B-SOON", new BigDecimal("30"), "柜 2", LocalDate.now().plusMonths(1)),
                "测试").id();

        Requisition r = requisitionService.submit("张三", new RequisitionRequest(
                other, new BigDecimal("50"), "效期测试"));
        requisitionService.review(r.getReqNo(), "李老师", new ReviewRequest(true, "同意"));
        Requisition issued = requisitionService.issue(r.getReqNo(), "王库管");

        assertEquals(0, issued.getIssuedQuantity().compareTo(new BigDecimal("50")));
        assertEquals(soonBatch, issued.getIssuedBatchId(), "应该先从快过期的那批出");
        assertEquals(0, batchRepository.currentQuantity(soonBatch).compareTo(BigDecimal.ZERO),
                "效期近的批次应该被出完");
        assertEquals(0, batchRepository.currentQuantity(lateBatch).compareTo(new BigDecimal("80")),
                "剩下的 20 从效期晚的批次出，它该剩 80");
    }

    @Test
    @DisplayName("台账能信：每个批次流水最后一笔的余额，等于该批次的当前余量")
    void ledgerBalanceMatchesBatch() {
        Requisition r = submit("张三", "120");
        requisitionService.review(r.getReqNo(), "李老师", new ReviewRequest(true, "同意"));
        requisitionService.issue(r.getReqNo(), "王库管");

        // 注意是**最后一笔**，不是每一笔：balanceAfter 记的是「那一刻的余额」，
        // 入库那笔记的是 500，之后出库成 380，拿入库那笔去比当然不等。
        // 对账的口径就是「流水最后一笔 vs 批次当前值」——这也是明细表存余额的用法。
        var lastByBatch = ledgerRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(
                        StockLedger::getBatchId,
                        l -> l,
                        (a, b) -> a.getId() > b.getId() ? a : b));

        assertTrue(!lastByBatch.isEmpty(), "应该有流水产生");
        lastByBatch.forEach((batchId, last) -> {
            BigDecimal actual = batchRepository.currentQuantity(batchId);
            assertEquals(0, actual.compareTo(last.getBalanceAfter()),
                    "批次 " + batchId + " 流水最后一笔（" + last.getChangeType() + "）记的余额 "
                            + last.getBalanceAfter() + "，但实际余量是 " + actual);
        });

        // 顺带把「每个批次都被对过」说清楚：本测试只有一个批次
        assertEquals(1, lastByBatch.size(), "本用例只涉及一个批次");
    }

    @Test
    @DisplayName("过期批次永远不被出库选中，哪怕它的效期排在最前面")
    void expiredBatchIsNeverIssued() {
        Long cid = chemicalService.create(new ChemicalRequest(
                "CAS-EXP-001", "带过期批次的试剂", "易燃", "中", "mL", "AR 500mL", null, null)).getId();

        // 直接建一条已过期的批次，模拟历史遗留数据——
        // 入库接口现在会挡掉过期批次，但库里可能本来就有（导入、手工改库、规则上线前留下的）
        Long expired = batchRepository.save(com.lazybone.hazmat.entity.ChemicalBatch.builder()
                .chemicalId(cid).batchNo("B-EXPIRED")
                .initialQuantity(new BigDecimal("100")).availableQuantity(new BigDecimal("100"))
                .location("报废区").expireDate(LocalDate.now().minusDays(1)).build()).getId();
        Long valid = stockService.inbound(cid,
                new InboundRequest("B-VALID", new BigDecimal("100"), "柜 1", LocalDate.now().plusMonths(6)),
                "测试").id();

        Requisition r = requisitionService.submit("张三", new RequisitionRequest(
                cid, new BigDecimal("50"), "效期测试"));
        requisitionService.review(r.getReqNo(), "李老师", new ReviewRequest(true, "同意"));
        Requisition issued = requisitionService.issue(r.getReqNo(), "王库管");

        assertEquals(valid, issued.getIssuedBatchId(), "必须从没过期的那批出");
        assertEquals(0, batchRepository.currentQuantity(expired).compareTo(new BigDecimal("100")),
                "过期批次一点都不能动，它只能走报废流程");
    }

    @Test
    @DisplayName("入库不能收已过期的批次")
    void cannotInboundExpiredBatch() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> stockService.inbound(chemicalId,
                        new InboundRequest("B-OLD", new BigDecimal("10"), "柜", LocalDate.now().minusDays(1)),
                        "测试"));
        assertTrue(e.getMessage().contains("已过期"), "实际: " + e.getMessage());
    }

    @Test
    @DisplayName("申请量超过库存：部分出库，状态推进到 ISSUED 并记下实发量")
    void overRequestIssuesPartially() {
        Requisition r = submit("张三", "9999");
        requisitionService.review(r.getReqNo(), "李老师", new ReviewRequest(true, "同意"));

        Requisition issued = requisitionService.issue(r.getReqNo(), "王库管");

        assertEquals(RequisitionStatus.ISSUED, issued.getStatus());
        assertEquals(0, issued.getIssuedQuantity().compareTo(new BigDecimal("500")),
                "申请 9999 但只有 500，就该发 500");
        assertTrue(requisitionService.detail(r.getReqNo()).timeline().stream()
                        .anyMatch(l -> l.getComment() != null && l.getComment().contains("部分出库")),
                "时间线里要写清是部分出库，否则看台账的人以为发足了");
    }

    @Test
    @DisplayName("库存真的空了再出库：报业务错，申请停在 APPROVED 不往下走")
    void issueWithEmptyStockStaysApproved() {
        Requisition first = submit("张三", "500");
        requisitionService.review(first.getReqNo(), "李老师", new ReviewRequest(true, "同意"));
        requisitionService.issue(first.getReqNo(), "王库管");

        Requisition second = submit("李四", "10");
        requisitionService.review(second.getReqNo(), "李老师", new ReviewRequest(true, "同意"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> requisitionService.issue(second.getReqNo(), "王库管"));
        assertTrue(e.getMessage().contains("库存不足"), "实际: " + e.getMessage());
        assertEquals(RequisitionStatus.APPROVED,
                requisitionService.detail(second.getReqNo()).requisition().getStatus(),
                "出库失败不该把申请推进到 ISSUED");
    }
}
