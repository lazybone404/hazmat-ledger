package com.lazybone.hazmat;

import com.lazybone.hazmat.common.BusinessException;
import com.lazybone.hazmat.dto.BatchResponse;
import com.lazybone.hazmat.dto.ChemicalRequest;
import com.lazybone.hazmat.dto.ExpiringBatchResponse;
import com.lazybone.hazmat.dto.InboundRequest;
import com.lazybone.hazmat.dto.ReconcileView;
import com.lazybone.hazmat.dto.StockView;
import com.lazybone.hazmat.entity.ChemicalBatch;
import com.lazybone.hazmat.repository.ChemicalBatchRepository;
import com.lazybone.hazmat.repository.ChemicalRepository;
import com.lazybone.hazmat.repository.StockLedgerRepository;
import com.lazybone.hazmat.service.ChemicalService;
import com.lazybone.hazmat.service.StockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开封管理测试。
 *
 * 核心断言只有一条：<b>开封之后，所有读取口径看到的都是 {@code usableUntil}，而不是包装有效期。</b>
 * 出库、库存视图、效期预警、报表四处都是独立实现的查询，所以每处都要有用例覆盖——
 * 只测 Service 的返回值，会漏掉某个查询仍在用 {@code expireDate} 的情况。
 *
 * <b>不标 {@code @Transactional}</b>：开封与出库都靠 Service 层事务真实提交，
 * 测试方法再包一层事务会让「跨事务重新读库」的行为失真，尤其是下面那几个
 * 「模拟时间流逝」的用例需要把改过的可用期真正落库。
 *
 * 用独立 H2 库名，避免与其它 {@code @SpringBootTest} 上下文的 create-drop 互相清表。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opening-it;DB_CLOSE_DELAY=-1",
        "hazmat.security.seed-users=false",
        "hazmat.ai.encode-on-startup=false"
})
class BatchOpeningTest {

    private static final String OPERATOR = "库管员";
    private static final AtomicInteger CAS_SEQ = new AtomicInteger();

    @Autowired private ChemicalService chemicalService;
    @Autowired private StockService stockService;
    @Autowired private ChemicalRepository chemicalRepository;
    @Autowired private ChemicalBatchRepository batchRepository;
    @Autowired private StockLedgerRepository ledgerRepository;

    @BeforeEach
    void setUp() {
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        ledgerRepository.deleteAll();
        batchRepository.deleteAll();
        chemicalRepository.deleteAll();
    }

    @Test
    @DisplayName("开封后可用截止日 = 开封日 + 开封后可用天数，并留下操作人")
    void openingShortensUsableUntilToPostOpenLimit() {
        Long chemicalId = chemical(30);
        LocalDate today = LocalDate.now();
        Long batchId = inbound(chemicalId, "B-OPEN-1", "100", today.plusDays(365));

        BatchResponse opened = stockService.openBatch(chemicalId, batchId, OPERATOR);

        assertEquals(today.plusDays(30), opened.usableUntil());
        assertEquals(today.plusDays(365), opened.expireDate(), "包装有效期本身不能被改");
        assertEquals(30, opened.daysToUsable());
        assertEquals(OPERATOR, opened.openedBy());
        assertNotNull(opened.openedAt());
    }

    @Test
    @DisplayName("包装有效期更早时保持包装有效期，开封不会延后可用期")
    void openingNeverExtendsPackageExpiry() {
        Long chemicalId = chemical(3650);
        LocalDate today = LocalDate.now();
        Long batchId = inbound(chemicalId, "B-OPEN-2", "100", today.plusDays(10));

        BatchResponse opened = stockService.openBatch(chemicalId, batchId, OPERATOR);

        assertEquals(today.plusDays(10), opened.usableUntil(),
                "可用期取 min(包装有效期, 开封日 + 天数)，不能因为开封就变长");
        assertEquals(today.plusDays(10), opened.expireDate());
    }

    @Test
    @DisplayName("档案没有配置开封后可用天数时只留痕，不改可用期")
    void openingWithoutPostOpenDaysOnlyRecordsTrace() {
        Long chemicalId = chemical(null);
        LocalDate today = LocalDate.now();
        Long batchId = inbound(chemicalId, "B-OPEN-3", "100", today.plusDays(365));

        BatchResponse opened = stockService.openBatch(chemicalId, batchId, OPERATOR);

        assertEquals(today.plusDays(365), opened.usableUntil());
        assertEquals(OPERATOR, opened.openedBy());
        assertNotNull(opened.openedAt());
    }

    @Test
    @DisplayName("重复开封被拒：覆盖时间等于凭空重新赋予保质期")
    void repeatedOpeningIsRejected() {
        Long chemicalId = chemical(30);
        Long batchId = inbound(chemicalId, "B-OPEN-4", "100", LocalDate.now().plusDays(365));

        stockService.openBatch(chemicalId, batchId, OPERATOR);

        assertThrows(BusinessException.class,
                () -> stockService.openBatch(chemicalId, batchId, OPERATOR));
    }

    @Test
    @DisplayName("已无库存的批次不能开封")
    void emptyBatchCannotBeOpened() {
        Long chemicalId = chemical(30);
        Long batchId = inbound(chemicalId, "B-OPEN-5", "100", LocalDate.now().plusDays(365));
        stockService.issue(chemicalId, new BigDecimal("100"), null, OPERATOR);

        assertThrows(BusinessException.class,
                () -> stockService.openBatch(chemicalId, batchId, OPERATOR));
    }

    @Test
    @DisplayName("批次不属于该试剂时拒绝开封，防止跨试剂改可用期")
    void openingBatchOfAnotherChemicalIsRejected() {
        Long ownerId = chemical(30);
        Long otherId = chemical(30);
        Long batchId = inbound(ownerId, "B-OPEN-6", "100", LocalDate.now().plusDays(365));

        assertThrows(BusinessException.class,
                () -> stockService.openBatch(otherId, batchId, OPERATOR));
    }

    @Test
    @DisplayName("开封后已过可用期的批次不会被出库选中")
    void openedBatchPastUsableUntilIsSkippedOnIssue() {
        Long chemicalId = chemical(30);
        LocalDate today = LocalDate.now();
        Long openedBatchId = inbound(chemicalId, "B-OPEN-OLD", "100", today.plusDays(365));
        Long freshBatchId = inbound(chemicalId, "B-OPEN-NEW", "100", today.plusDays(400));

        stockService.openBatch(chemicalId, openedBatchId, OPERATOR);
        // 模拟三十天后：开封那批已经超过可用期，包装有效期却还剩大半年
        expireUsableUntil(openedBatchId, today.minusDays(1));

        StockService.IssueOutcome outcome =
                stockService.issue(chemicalId, new BigDecimal("50"), null, OPERATOR);

        assertEquals(1, outcome.allocations().size());
        assertEquals("B-OPEN-NEW", outcome.allocations().get(0).batchNo(),
                "开封后过期的批次必须被跳过，哪怕它的包装有效期最靠前");
        assertEquals(0, new BigDecimal("100").compareTo(batchRepository.currentQuantity(openedBatchId)));
        assertEquals(0, new BigDecimal("50").compareTo(batchRepository.currentQuantity(freshBatchId)));
    }

    @Test
    @DisplayName("库存视图的可用总量排除开封后过期的批次，并在预警里说明是开封导致")
    void stockViewCountsOnlyUsableAndReportsOpenedExpiry() {
        Long chemicalId = chemical(30);
        LocalDate today = LocalDate.now();
        Long batchId = inbound(chemicalId, "B-OPEN-7", "100", today.plusDays(365));
        stockService.openBatch(chemicalId, batchId, OPERATOR);
        expireUsableUntil(batchId, today.minusDays(1));

        StockView view = stockService.stockView(chemicalId);

        assertEquals(0, BigDecimal.ZERO.compareTo(view.totalAvailable()));
        assertTrue(view.alerts().stream().anyMatch(a -> a.contains("开封后已超过可用期")),
                "预警要说清是开封导致的，而不是笼统的「已过期」");
    }

    @Test
    @DisplayName("效期预警按可用截止日排序与计算天数，不看包装有效期")
    void expiringAlertUsesUsableUntil() {
        Long chemicalId = chemical(5);
        LocalDate today = LocalDate.now();
        Long batchId = inbound(chemicalId, "B-OPEN-8", "100", today.plusDays(365));
        stockService.openBatch(chemicalId, batchId, OPERATOR);

        var page = stockService.expiring(30, PageRequest.of(0, 20, Sort.by("usableUntil")));

        List<ExpiringBatchResponse> content = page.content();
        assertEquals(1, content.size());
        assertEquals("B-OPEN-8", content.get(0).batchNo());
        assertEquals(today.plusDays(5), content.get(0).usableUntil());
        assertEquals(5, content.get(0).daysToUsable());
        assertEquals(today.plusDays(365), content.get(0).expireDate());
    }

    @Test
    @DisplayName("开封不改数量、不写流水，对账结果保持不变")
    void openingDoesNotTouchLedgerOrBalance() {
        Long chemicalId = chemical(30);
        Long batchId = inbound(chemicalId, "B-OPEN-9", "100", LocalDate.now().plusDays(365));

        ReconcileView before = stockService.reconcile(chemicalId);
        long ledgerBefore = ledgerRepository.count();

        stockService.openBatch(chemicalId, batchId, OPERATOR);

        ReconcileView after = stockService.reconcile(chemicalId);
        assertEquals(ledgerBefore, ledgerRepository.count(), "开封不应写台账流水");
        assertTrue(before.consistent());
        assertTrue(after.consistent());
        assertEquals(0, new BigDecimal("100").compareTo(batchRepository.currentQuantity(batchId)));
    }

    @Test
    @DisplayName("并发开封只有一个成功，另一次被数据库的条件更新挡住")
    void concurrentOpeningOnlyOneWins() throws Exception {
        Long chemicalId = chemical(30);
        Long batchId = inbound(chemicalId, "B-OPEN-10", "100", LocalDate.now().plusDays(365));

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    stockService.openBatch(chemicalId, batchId, OPERATOR);
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    rejected.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(1, success.get(), "只允许一个请求开封成功");
        assertEquals(1, rejected.get(), "另一个必须被拒，不能两个都写成功");
        assertNotNull(batchRepository.findById(batchId).orElseThrow().getOpenedAt());
    }

    // ---------------------------------------------------------------- 辅助

    private Long chemical(Integer postOpenDays) {
        int seq = CAS_SEQ.incrementAndGet();
        return chemicalService.create(new ChemicalRequest(
                "CAS-OPEN-" + String.format("%04d", seq), "开封测试试剂",
                "易燃", "中", "mL", "AR 500mL", null, postOpenDays)).getId();
    }

    private Long inbound(Long chemicalId, String batchNo, String quantity, LocalDate expireDate) {
        return stockService.inbound(chemicalId,
                new InboundRequest(batchNo, new BigDecimal(quantity), "危化品柜 A-1", expireDate),
                OPERATOR).id();
    }

    /**
     * 把可用截止日改到过去，模拟「开封后过了若干天」。
     *
     * 时间推进类逻辑没有可注入的时钟，只能直接改库。这里改的是 {@code usableUntil}
     * 而不是 {@code openedAt}，因为开封后可用期在开封那一刻就已经算定，
     * 之后再怎么改开封时间也不会自动重算——这正是「物化一列」的代价与好处。
     */
    private void expireUsableUntil(Long batchId, LocalDate date) {
        ChemicalBatch batch = batchRepository.findById(batchId).orElseThrow();
        batch.setUsableUntil(date);
        batchRepository.save(batch);
    }
}
