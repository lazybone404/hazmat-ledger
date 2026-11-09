package com.lazybone.hazmat;

import com.lazybone.hazmat.common.BusinessException;
import com.lazybone.hazmat.common.ConflictException;
import com.lazybone.hazmat.dto.ChemicalRequest;
import com.lazybone.hazmat.dto.InboundRequest;
import com.lazybone.hazmat.dto.ScrapOrderRequest;
import com.lazybone.hazmat.dto.ScrapReviewRequest;
import com.lazybone.hazmat.entity.ScrapOrder;
import com.lazybone.hazmat.entity.ScrapOrderStatus;
import com.lazybone.hazmat.entity.StockLedger;
import com.lazybone.hazmat.repository.ChemicalBatchRepository;
import com.lazybone.hazmat.repository.ChemicalRepository;
import com.lazybone.hazmat.repository.ScrapOrderRepository;
import com.lazybone.hazmat.repository.StockLedgerRepository;
import com.lazybone.hazmat.service.ChemicalService;
import com.lazybone.hazmat.service.ScrapOrderService;
import com.lazybone.hazmat.service.StockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报废审批单。
 *
 * <p>不标 {@code @Transactional}：并发审批需要两个真实事务；自动驳回那条还需要
 * 确认「异常抛出前单据已经提交为 REJECTED」，声明式事务会把整条流程包成一个事务，
 * 测不到提交时机。
 */
@SpringBootTest
class ScrapOrderTest {

    @Autowired private ChemicalService chemicalService;
    @Autowired private StockService stockService;
    @Autowired private ScrapOrderService scrapOrderService;
    @Autowired private ScrapOrderRepository scrapOrderRepository;
    @Autowired private ChemicalRepository chemicalRepository;
    @Autowired private ChemicalBatchRepository batchRepository;
    @Autowired private StockLedgerRepository ledgerRepository;

    private Long chemicalId;
    private Long batchId;

    @BeforeEach
    void setUp() {
        cleanUp();
        chemicalId = chemicalService.create(new ChemicalRequest(
                "CAS-SCRAP-ORDER-001", "报废审批测试试剂", "易燃", "中", "mL", "AR 500mL",
                null, null)).getId();
        batchId = stockService.inbound(chemicalId,
                new InboundRequest("B-SO-1", new BigDecimal("100"), "危化品柜 C-1",
                        LocalDate.now().plusYears(1)), "测试").id();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        scrapOrderRepository.deleteAll();
        ledgerRepository.deleteAll();
        batchRepository.deleteAll();
        chemicalRepository.deleteAll();
    }

    private ScrapOrder create(String applicant, String quantity) {
        return scrapOrderService.create(new ScrapOrderRequest(
                chemicalId, batchId, new BigDecimal(quantity), "过期处理"), applicant);
    }

    @Test
    @DisplayName("创建后待审，通过后只扣一次并写 SCRAP_ORDER 流水")
    void approveDeductsOnceAndWritesScrapOrderLedger() {
        ScrapOrder order = create("alice", "30");
        assertEquals(ScrapOrderStatus.PENDING, order.getStatus());

        ScrapOrder approved = scrapOrderService.review(order.getScrapNo(), "approver1",
                new ScrapReviewRequest(true, "同意报废")).order();

        assertEquals(ScrapOrderStatus.APPROVED, approved.getStatus());
        assertEquals("approver1", approved.getReviewer());
        assertEquals(0, batchRepository.currentQuantity(batchId).compareTo(new BigDecimal("70")),
                "通过后批次应只剩 70");

        List<StockLedger> ledgers = ledgerRepository.findByRefTypeAndRefId("SCRAP_ORDER", order.getId());
        assertEquals(1, ledgers.size(), "一次审批只应写一条流水");
        assertEquals(0, ledgers.get(0).getQuantity().compareTo(new BigDecimal("-30")));
        assertEquals(0, ledgers.get(0).getBalanceAfter().compareTo(new BigDecimal("70")));
    }

    @Test
    @DisplayName("驳回不动库存")
    void rejectDoesNotTouchStock() {
        ScrapOrder order = create("alice", "30");

        ScrapOrder rejected = scrapOrderService.review(order.getScrapNo(), "approver1",
                new ScrapReviewRequest(false, "数量存疑")).order();

        assertEquals(ScrapOrderStatus.REJECTED, rejected.getStatus());
        assertEquals(0, batchRepository.currentQuantity(batchId).compareTo(new BigDecimal("100")));
        assertTrue(ledgerRepository.findByRefTypeAndRefId("SCRAP_ORDER", order.getId()).isEmpty());
    }

    @Test
    @DisplayName("申请人不能审批自己的报废单")
    void applicantCannotReviewOwnOrder() {
        ScrapOrder order = create("alice", "30");

        assertThrows(BusinessException.class, () -> scrapOrderService.review(
                order.getScrapNo(), "alice", new ScrapReviewRequest(true, "自己批")));

        assertEquals(0, batchRepository.currentQuantity(batchId).compareTo(new BigDecimal("100")));
    }

    @Test
    @DisplayName("撤销只允许本人、只允许待审单")
    void onlyApplicantCanCancelPendingOrder() {
        ScrapOrder order = create("alice", "30");

        assertThrows(BusinessException.class, () ->
                scrapOrderService.cancel(order.getScrapNo(), "bob"));

        ScrapOrder cancelled = scrapOrderService.cancel(order.getScrapNo(), "alice");
        assertEquals(ScrapOrderStatus.CANCELLED, cancelled.getStatus());

        assertThrows(BusinessException.class, () ->
                scrapOrderService.review(order.getScrapNo(), "approver1",
                        new ScrapReviewRequest(true, "已经撤销")));
    }

    @Test
    @DisplayName("创建时超过当前可用量直接拒绝")
    void cannotCreateBeyondAvailable() {
        assertThrows(BusinessException.class, () -> create("alice", "101"));
        assertTrue(scrapOrderRepository.findAll().isEmpty());
    }

    /**
     * 这条覆盖「创建时都够、审批时不够」的真实窗口：
     * 申请 80 时库存 100；随后同一批次被应急报废 30，只剩 70。
     * 审批必须自动驳回并提交这个状态，而不是回滚后把单据留在 PENDING。
     */
    @Test
    @DisplayName("审批时余量已变化：单据自动驳回，库存不动，返回 409 语义的冲突异常")
    void approveAutoRejectsWhenStockChanged() {
        ScrapOrder order = create("alice", "80");
        stockService.scrap(chemicalId, batchId,
                new com.lazybone.hazmat.dto.ScrapRequest(new BigDecimal("30"), "应急报废"), "admin");

        assertThrows(ConflictException.class, () -> scrapOrderService.review(
                order.getScrapNo(), "approver1", new ScrapReviewRequest(true, "同意")));

        ScrapOrder reloaded = scrapOrderRepository.findByScrapNo(order.getScrapNo()).orElseThrow();
        assertEquals(ScrapOrderStatus.REJECTED, reloaded.getStatus(),
                "扣减失败必须把单据置为 REJECTED，不能停在 PENDING");
        assertTrue(reloaded.getReviewComment().contains("可用量不足"));
        assertEquals(0, batchRepository.currentQuantity(batchId).compareTo(new BigDecimal("70")),
                "自动驳回不能改动库存");
    }

    @Test
    @DisplayName("两个人同时审批同一张单：只有一次成功，库存只扣一次")
    void concurrentReviewSucceedsOnce() throws Exception {
        ScrapOrder order = create("alice", "30");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();

        for (String reviewer : List.of("approver1", "approver2")) {
            pool.submit(() -> {
                try {
                    start.await();
                    scrapOrderService.review(order.getScrapNo(), reviewer,
                            new ScrapReviewRequest(true, "同意"));
                    succeeded.incrementAndGet();
                } catch (BusinessException | ConflictException e) {
                    // 另一方必须失败，这是预期结果
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "审批线程没有在 30 秒内结束");

        assertEquals(1, succeeded.get(), "并发审批只能有一次成功");
        assertEquals(0, batchRepository.currentQuantity(batchId).compareTo(new BigDecimal("70")),
                "库存只能扣一次");
        assertEquals(1, ledgerRepository.findByRefTypeAndRefId("SCRAP_ORDER", order.getId()).size());
    }
}
