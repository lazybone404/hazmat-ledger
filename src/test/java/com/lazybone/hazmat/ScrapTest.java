package com.lazybone.hazmat;

import com.lazybone.hazmat.common.BusinessException;
import com.lazybone.hazmat.dto.ChemicalRequest;
import com.lazybone.hazmat.dto.InboundRequest;
import com.lazybone.hazmat.dto.ScrapRequest;
import com.lazybone.hazmat.dto.ScrapResult;
import com.lazybone.hazmat.entity.StockLedger;
import com.lazybone.hazmat.repository.ChemicalBatchRepository;
import com.lazybone.hazmat.repository.ChemicalRepository;
import com.lazybone.hazmat.repository.RequisitionLogRepository;
import com.lazybone.hazmat.repository.RequisitionRepository;
import com.lazybone.hazmat.repository.StockLedgerRepository;
import com.lazybone.hazmat.service.ChemicalService;
import com.lazybone.hazmat.service.StockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报废闭环测试。
 *
 * <b>不标 {@code @Transactional}</b>：并发那条测试需要多个真实事务同时抢同一批次，
 * 声明式事务会让 30 个线程共享同一个持久化上下文，测的就不是并发了。
 *
 * 过期批次的构造方式：入库日期设为 400 天前、有效期设为 100 天前。
 * 这样「入库那一刻还没过期」（能通过入库校验），「今天已经过期」（只能报废、不能出库）。
 */
@SpringBootTest
class ScrapTest {

    private static final int THREADS = 30;
    private static final BigDecimal STOCK = new BigDecimal("100");
    private static final BigDecimal PER_SCRAP = new BigDecimal("10");

    @Autowired private ChemicalService chemicalService;
    @Autowired private StockService stockService;
    @Autowired private ChemicalRepository chemicalRepository;
    @Autowired private ChemicalBatchRepository batchRepository;
    @Autowired private StockLedgerRepository ledgerRepository;
    @Autowired private RequisitionRepository requisitionRepository;
    @Autowired private RequisitionLogRepository requisitionLogRepository;

    private Long chemicalId;
    private Long expiredBatchId;
    private Long freshBatchId;

    @BeforeEach
    void setUp() {
        cleanUp();
        chemicalId = chemicalService.create(new ChemicalRequest(
                "CAS-SCRAP-001", "报废测试试剂", "易燃", "中", "mL", "AR 500mL", null, null)).getId();

        // 入库时未过期、今天已过期：能入库，但不能出库，只能报废
        expiredBatchId = stockService.inbound(chemicalId,
                new InboundRequest("B-EXP-1", STOCK, "危化品柜 A-1", LocalDate.now().minusDays(100)),
                "测试", LocalDate.now().minusDays(400)).id();

        freshBatchId = stockService.inbound(chemicalId,
                new InboundRequest("B-FRESH-1", new BigDecimal("50"), "危化品柜 A-2",
                        LocalDate.now().plusYears(1)),
                "测试").id();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        requisitionLogRepository.deleteAll();
        requisitionRepository.deleteAll();
        ledgerRepository.deleteAll();
        batchRepository.deleteAll();
        chemicalRepository.deleteAll();
    }

    @Test
    @DisplayName("过期批次可以报废，流水余额与批次余量对得上")
    void expiredBatchCanBeScrappedAndLedgerBalances() {
        ScrapResult result = stockService.scrap(chemicalId, expiredBatchId,
                new ScrapRequest(new BigDecimal("30"), "已过期"), "安全员");

        assertEquals(0, result.remainingQuantity().compareTo(new BigDecimal("70")),
                "报废 30 后应剩 70，实际 " + result.remainingQuantity());
        assertEquals(0, batchRepository.currentQuantity(expiredBatchId)
                .compareTo(new BigDecimal("70")), "批次余量应与返回结果一致");

        StockLedger scrap = ledgerRepository.findByBatchIdOrderByCreatedAtAscIdAsc(expiredBatchId).stream()
                .filter(l -> "SCRAP".equals(l.getChangeType()))
                .findFirst().orElseThrow();

        assertEquals(0, scrap.getQuantity().compareTo(new BigDecimal("-30")),
                "报废在流水里记负数，实际 " + scrap.getQuantity());
        assertEquals(0, scrap.getBalanceAfter().compareTo(new BigDecimal("70")),
                "流水余额必须是扣减后的真实值，实际 " + scrap.getBalanceAfter());
        assertEquals("安全员", scrap.getOperator());
        assertEquals("已过期", result.reason());
    }

    @Test
    @DisplayName("报废量超过批次可用量时拒绝，余量保持不变")
    void scrapMoreThanAvailableIsRejected() {
        assertThrows(BusinessException.class, () ->
                stockService.scrap(chemicalId, expiredBatchId,
                        new ScrapRequest(new BigDecimal("101"), "已过期"), "测试"));

        assertEquals(0, batchRepository.currentQuantity(expiredBatchId).compareTo(STOCK),
                "被拒绝的报废不能改动库存");
    }

    @Test
    @DisplayName("批次不属于该试剂时拒绝，防止跨试剂误扣")
    void batchMustBelongToChemical() {
        Long otherChemicalId = chemicalService.create(new ChemicalRequest(
                "CAS-SCRAP-002", "另一种试剂", "腐蚀", "中", "mL", "AR 500mL", null, null)).getId();

        assertThrows(BusinessException.class, () ->
                stockService.scrap(otherChemicalId, freshBatchId,
                        new ScrapRequest(new BigDecimal("1"), "误操作"), "测试"));

        assertEquals(0, batchRepository.currentQuantity(freshBatchId).compareTo(new BigDecimal("50")),
                "跨试剂报废不能扣掉别人的库存");
    }

    @Test
    @DisplayName("未过期批次也能报废（容器破损），原因会记入流水")
    void freshBatchCanBeScrappedForDamage() {
        ScrapResult result = stockService.scrap(chemicalId, freshBatchId,
                new ScrapRequest(new BigDecimal("5"), "容器破损"), "安全员");

        assertEquals(0, result.remainingQuantity().compareTo(new BigDecimal("45")));
        assertEquals("容器破损", result.reason());
    }

    @Test
    @DisplayName("30 个线程各报废 10，库存 100：恰好 10 次成功，余量归零，不超扣")
    void concurrentScrapNeverOverDeducts() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    stockService.scrap(chemicalId, expiredBatchId,
                            new ScrapRequest(PER_SCRAP, "并发报废测试"), "并发测试");
                    succeeded.incrementAndGet();
                } catch (BusinessException e) {
                    // 库存不足是预期的失败，不会造成超扣
                } catch (Throwable t) {
                    // 锁等待超时之类的异常不计入成功，由下面的总量断言兜住
                    unexpected.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "线程池没在 60 秒内跑完，可能卡在锁上");
        pool.shutdownNow();

        BigDecimal remaining = batchRepository.currentQuantity(expiredBatchId);
        BigDecimal scrappedTotal = ledgerRepository.findByBatchIdOrderByCreatedAtAscIdAsc(expiredBatchId).stream()
                .filter(l -> "SCRAP".equals(l.getChangeType()))
                .map(StockLedger::getQuantity)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(0, remaining.compareTo(BigDecimal.ZERO),
                "库存应被报废完，实际余量 " + remaining + "（未预期异常: " + unexpected.get() + "）");
        assertEquals(0, scrappedTotal.compareTo(STOCK),
                "流水里的报废总量必须恰好等于库存，实际 " + scrappedTotal);
        assertEquals(STOCK.divide(PER_SCRAP).intValue(), succeeded.get(),
                "成功次数应与库存/单次量一致，实际 " + succeeded.get());
    }
}
