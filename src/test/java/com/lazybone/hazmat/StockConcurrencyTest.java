package com.lazybone.hazmat;

import com.lazybone.hazmat.common.BusinessException;
import com.lazybone.hazmat.dto.ChemicalRequest;
import com.lazybone.hazmat.dto.InboundRequest;
import com.lazybone.hazmat.entity.Chemical;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发出库测试。这是本项目的核心证据，也是**这个类为什么不能标 {@code @Transactional}**：
 *
 * 标上的话整个测试方法跑在一个事务里，30 个线程会共享同一个事务和同一个
 * 持久化上下文——那测的就不是并发，是「一个事务里调 30 次」。条件更新的原子性
 * 恰恰只在**多个事务真的同时打同一行**时才体现出来。所以这里不用声明式事务，
 * 改为在每个测试前后手工清理数据。
 *
 * 断言的不变量有两条，缺一不可：
 * <ol>
 *   <li>发出去的**总量恰好等于库存**——少了说明有请求被误判为库存不足，
 *       多了说明超发。只断言「不超过」的话，一个全部失败的实现也能过。</li>
 *   <li>批次余量**归零**，且流水余额与批次余量**对得上**——这是台账能不能信的问题。</li>
 * </ol>
 */
@SpringBootTest
class StockConcurrencyTest {

    private static final int THREADS = 30;
    private static final BigDecimal STOCK = new BigDecimal("100");
    private static final BigDecimal PER_REQUEST = new BigDecimal("10");

    @Autowired
    private ChemicalService chemicalService;

    @Autowired
    private StockService stockService;

    @Autowired
    private ChemicalRepository chemicalRepository;

    @Autowired
    private ChemicalBatchRepository batchRepository;

    @Autowired
    private StockLedgerRepository ledgerRepository;

    @Autowired
    private RequisitionRepository requisitionRepository;

    @Autowired
    private RequisitionLogRepository requisitionLogRepository;

    private Long chemicalId;
    private Long batchId;

    @BeforeEach
    void setUp() {
        cleanUp();
        Chemical chemical = chemicalService.create(new ChemicalRequest(
                "CAS-TEST-001", "并发测试试剂", "易燃", "中", "mL", "AR 500mL", null, null));
        chemicalId = chemical.getId();
        batchId = stockService.inbound(chemicalId,
                new InboundRequest("B-CONC-1", STOCK, "测试柜", LocalDate.now().plusYears(1)),
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
    @DisplayName("30 个线程各领 10，库存 100：恰好 10 个成功，余量归零，不超发")
    void concurrentIssueNeverOverIssues() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        for (int i = 0; i < THREADS; i++) {
            final int seq = i;
            pool.submit(() -> {
                try {
                    start.await();
                    StockService.IssueOutcome outcome =
                            stockService.issue(chemicalId, PER_REQUEST, (long) seq, "并发测试");
                    if (!outcome.isEmpty()) {
                        succeeded.incrementAndGet();
                    }
                } catch (Throwable t) {
                    // 锁等待超时之类的异常不算失败——它没有被计入成功，不会造成超发。
                    // 真正要暴露的是「明明没库存却成功了」，那由下面的总量断言兜住。
                    unexpected.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "线程池没在 60 秒内跑完，可能卡在锁上");
        pool.shutdownNow();

        BigDecimal remaining = batchRepository.currentQuantity(batchId);
        BigDecimal issuedTotal = ledgerRepository.findAll().stream()
                .filter(l -> "OUT".equals(l.getChangeType()))
                .map(StockLedger::getQuantity)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(0, remaining.compareTo(BigDecimal.ZERO),
                "库存应该被领完，实际余量 " + remaining + "（未预期异常: " + unexpected.get() + "）");
        assertEquals(0, issuedTotal.compareTo(STOCK),
                "流水里的出库总量必须恰好等于库存，实际 " + issuedTotal);
        assertEquals(STOCK.divide(PER_REQUEST).intValue(), succeeded.get(),
                "成功次数应与库存/单次量一致，实际 " + succeeded.get());
    }

    @Test
    @DisplayName("并发用同一个批次号入库：只有一条落库，其余被唯一索引挡下")
    void concurrentInboundWithSameBatchNoKeepsOnlyOne() throws Exception {
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    stockService.inbound(chemicalId,
                            new InboundRequest("B-SAME", BigDecimal.ONE, "测试柜",
                                    LocalDate.now().plusYears(1)),
                            "并发测试");
                    succeeded.incrementAndGet();
                } catch (BusinessException e) {
                    // 应用层的 exists 检查与数据库唯一索引都会走到这里
                    rejected.incrementAndGet();
                } catch (Throwable t) {
                    unexpected.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "线程池没在 60 秒内跑完，可能卡在锁上");
        pool.shutdownNow();

        long stored = batchRepository.findByChemicalIdOrderByExpireDateAsc(chemicalId).stream()
                .filter(b -> "B-SAME".equals(b.getBatchNo()))
                .count();
        assertEquals(1, succeeded.get(),
                "同一批次号只应成功一次（未预期异常: " + unexpected.get() + "）");
        assertEquals(threads - 1, rejected.get(), "其余请求都应被拒绝，而不是落成重复批次");
        assertEquals(1, stored, "库里只能有一条 B-SAME");
    }

    @Test
    @DisplayName("单次领用超过库存：把现有的全发出去（部分出库是设计选择，不是缺陷）")
    void issueMoreThanStockIssuesWhateverIsAvailable() {
        StockService.IssueOutcome outcome =
                stockService.issue(chemicalId, new BigDecimal("999"), 1L, "测试");

        assertEquals(0, outcome.issuedQuantity().compareTo(STOCK),
                "库存只有 " + STOCK + "，就该发这么多");
        assertEquals(0, batchRepository.currentQuantity(batchId).compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("库存真的为 0 时才返回空结果——这才是「发不出去」")
    void emptyStockGivesEmptyOutcome() {
        stockService.issue(chemicalId, STOCK, 1L, "测试");
        assertEquals(0, batchRepository.currentQuantity(batchId).compareTo(BigDecimal.ZERO));

        StockService.IssueOutcome outcome =
                stockService.issue(chemicalId, new BigDecimal("1"), 2L, "测试");

        assertTrue(outcome.isEmpty(), "库存为 0 时不该发出任何东西");
    }

    @Test
    @DisplayName("库存只够一部分时按量发出，流水里的余额与实际余量一致")
    void partialIssueKeepsLedgerConsistent() {
        StockService.IssueOutcome outcome =
                stockService.issue(chemicalId, new BigDecimal("150"), 1L, "测试");

        assertEquals(0, outcome.issuedQuantity().compareTo(STOCK), "应该把现有 100 全发出去");
        BigDecimal remaining = batchRepository.currentQuantity(batchId);
        assertEquals(0, remaining.compareTo(BigDecimal.ZERO));

        BigDecimal lastBalance = ledgerRepository.findAll().stream()
                .filter(l -> "OUT".equals(l.getChangeType()))
                .map(StockLedger::getBalanceAfter)
                .reduce((a, b) -> b)
                .orElseThrow();
        assertEquals(0, lastBalance.compareTo(remaining), "最后一笔流水的余额该等于批次实际余量");
    }
}
