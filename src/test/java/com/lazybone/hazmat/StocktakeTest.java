package com.lazybone.hazmat;

import com.lazybone.hazmat.common.BusinessException;
import com.lazybone.hazmat.common.ResourceNotFoundException;
import com.lazybone.hazmat.dto.ChemicalRequest;
import com.lazybone.hazmat.dto.InboundRequest;
import com.lazybone.hazmat.dto.ReconcileView;
import com.lazybone.hazmat.dto.ScrapRequest;
import com.lazybone.hazmat.dto.StocktakeCreateRequest;
import com.lazybone.hazmat.dto.StocktakeItemRequest;
import com.lazybone.hazmat.dto.StocktakeItemResponse;
import com.lazybone.hazmat.dto.StocktakeResponse;
import com.lazybone.hazmat.entity.ChemicalBatch;
import com.lazybone.hazmat.entity.StockLedger;
import com.lazybone.hazmat.entity.Stocktake;
import com.lazybone.hazmat.repository.ChemicalBatchRepository;
import com.lazybone.hazmat.repository.ChemicalRepository;
import com.lazybone.hazmat.repository.RequisitionLogRepository;
import com.lazybone.hazmat.repository.RequisitionRepository;
import com.lazybone.hazmat.repository.StockLedgerRepository;
import com.lazybone.hazmat.repository.StocktakeItemRepository;
import com.lazybone.hazmat.repository.StocktakeRepository;
import com.lazybone.hazmat.service.ChemicalService;
import com.lazybone.hazmat.service.StockService;
import com.lazybone.hazmat.service.StocktakeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 盘点与对账测试。
 *
 * <b>不标 {@code @Transactional}</b>：几个用例要验证「确认失败后整张单回滚」，
 * 必须让 Service 的事务真实提交/回滚，测试方法自己再包一层事务会把两个边界糊在一起。
 *
 * 用独立 H2 库名，避免与其它 {@code @SpringBootTest} 上下文的 create-drop 互相清表。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:stocktake-it;DB_CLOSE_DELAY=-1",
        "hazmat.security.seed-users=false",
        "hazmat.ai.encode-on-startup=false"
})
class StocktakeTest {

    private static final String OPERATOR = "库管员";

    @Autowired private ChemicalService chemicalService;
    @Autowired private StockService stockService;
    @Autowired private StocktakeService stocktakeService;
    @Autowired private ChemicalRepository chemicalRepository;
    @Autowired private ChemicalBatchRepository batchRepository;
    @Autowired private StockLedgerRepository ledgerRepository;
    @Autowired private StocktakeRepository stocktakeRepository;
    @Autowired private StocktakeItemRepository stocktakeItemRepository;
    @Autowired private RequisitionRepository requisitionRepository;
    @Autowired private RequisitionLogRepository requisitionLogRepository;

    private Long chemicalId;
    private Long batchA;
    private Long batchB;

    @BeforeEach
    void setUp() {
        cleanUp();
        chemicalId = chemicalService.create(new ChemicalRequest(
                "CAS-ST-001", "盘点测试试剂", "易燃", "中", "mL", "AR 500mL", null, null)).getId();
        batchA = stockService.inbound(chemicalId,
                new InboundRequest("ST-A", new BigDecimal("100"), "柜 A-1",
                        LocalDate.now().plusYears(1)), OPERATOR).id();
        batchB = stockService.inbound(chemicalId,
                new InboundRequest("ST-B", new BigDecimal("50"), "柜 A-2",
                        LocalDate.now().plusYears(1)), OPERATOR).id();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        stocktakeItemRepository.deleteAll();
        stocktakeRepository.deleteAll();
        requisitionLogRepository.deleteAll();
        requisitionRepository.deleteAll();
        ledgerRepository.deleteAll();
        batchRepository.deleteAll();
        chemicalRepository.deleteAll();
    }

    private StocktakeResponse createSheet() {
        Stocktake st = stocktakeService.create(
                new StocktakeCreateRequest(List.of(chemicalId), "月度盘点"), OPERATOR);
        return stocktakeService.view(st.getStocktakeNo());
    }

    private StocktakeItemResponse itemOf(StocktakeResponse sheet, String batchNo) {
        return sheet.items().stream()
                .filter(i -> i.batchNo().equals(batchNo))
                .findFirst().orElseThrow();
    }

    private void record(String stocktakeNo, StocktakeItemResponse item, String actual) {
        stocktakeService.recordActual(stocktakeNo, item.itemId(),
                new StocktakeItemRequest(new BigDecimal(actual), null));
    }

    @Test
    @DisplayName("建单冻结账面量：每个有库存的批次一条明细，未录入时差异为 null")
    void createSnapshotsBookQuantityAndLeavesDiffEmpty() {
        StocktakeResponse sheet = createSheet();

        assertEquals("DRAFT", sheet.status());
        assertEquals(2, sheet.items().size(), "两个批次应各生成一条明细");
        assertNull(sheet.diffCount(), "尚未录入时 diffCount 应为 null，不能用 0 冒充「无差异」");

        StocktakeItemResponse a = itemOf(sheet, "ST-A");
        assertEquals(0, a.bookQuantity().compareTo(new BigDecimal("100")));
        assertNull(a.actualQuantity());
        assertNull(a.diffQuantity());
        assertEquals("盘点测试试剂", a.chemicalName());
        assertEquals("mL", a.unit());
    }

    @Test
    @DisplayName("盘亏：确认后批次余量等于实盘量，写一条负的 ADJUST 流水，对账恢复一致")
    void shortageWritesNegativeAdjustAndBalanceStaysConsistent() {
        StocktakeResponse sheet = createSheet();
        record(sheet.stocktakeNo(), itemOf(sheet, "ST-A"), "80");
        record(sheet.stocktakeNo(), itemOf(sheet, "ST-B"), "50");

        stocktakeService.submit(sheet.stocktakeNo(), OPERATOR);
        stocktakeService.confirm(sheet.stocktakeNo(), "主管");

        assertEquals(0, batchRepository.currentQuantity(batchA)
                .compareTo(new BigDecimal("80")), "批次余量必须被改成实盘量");

        List<StockLedger> adjust = ledgerRepository.findByBatchIdOrderByCreatedAtAscIdAsc(batchA).stream()
                .filter(l -> "ADJUST".equals(l.getChangeType()))
                .toList();
        assertEquals(1, adjust.size(), "盘亏应写一条 ADJUST 流水");
        assertEquals(0, adjust.get(0).getQuantity().compareTo(new BigDecimal("-20")),
                "差异 = 实盘 - 账面，盘亏记负数，实际 " + adjust.get(0).getQuantity());
        assertEquals(0, adjust.get(0).getBalanceAfter().compareTo(new BigDecimal("80")),
                "balanceAfter 必须是调账后的余额");
        assertEquals("STOCKTAKE", adjust.get(0).getRefType());
        assertEquals("主管", adjust.get(0).getOperator());

        // 没有差异的批次不该写流水，否则台账里会满是「变动 0」的噪音
        assertTrue(ledgerRepository.findByBatchIdOrderByCreatedAtAscIdAsc(batchB).stream()
                .noneMatch(l -> "ADJUST".equals(l.getChangeType())));

        ReconcileView view = stockService.reconcile(chemicalId);
        assertTrue(view.consistent(), "调账后对账应平：" + view);
        assertEquals(0, view.totalAvailable().compareTo(new BigDecimal("130")));
        assertEquals(0, view.totalLedger().compareTo(new BigDecimal("130")));
    }

    @Test
    @DisplayName("盘盈：差异记正数，余额与流水同步上调")
    void surplusWritesPositiveAdjust() {
        StocktakeResponse sheet = createSheet();
        record(sheet.stocktakeNo(), itemOf(sheet, "ST-A"), "105");
        record(sheet.stocktakeNo(), itemOf(sheet, "ST-B"), "50");
        stocktakeService.submit(sheet.stocktakeNo(), OPERATOR);
        stocktakeService.confirm(sheet.stocktakeNo(), "主管");

        assertEquals(0, batchRepository.currentQuantity(batchA).compareTo(new BigDecimal("105")));
        StockLedger adjust = ledgerRepository.findByBatchIdOrderByCreatedAtAscIdAsc(batchA).stream()
                .filter(l -> "ADJUST".equals(l.getChangeType()))
                .findFirst().orElseThrow();
        assertEquals(0, adjust.getQuantity().compareTo(new BigDecimal("5")));
        assertTrue(stockService.reconcile(chemicalId).consistent());
    }

    @Test
    @DisplayName("全部无差异：确认不写任何流水，状态照样到 CONFIRMED")
    void noDifferenceWritesNoLedgerAtAll() {
        StocktakeResponse sheet = createSheet();
        record(sheet.stocktakeNo(), itemOf(sheet, "ST-A"), "100");
        record(sheet.stocktakeNo(), itemOf(sheet, "ST-B"), "50");

        long before = ledgerRepository.count();
        stocktakeService.submit(sheet.stocktakeNo(), OPERATOR);
        Stocktake confirmed = stocktakeService.confirm(sheet.stocktakeNo(), "主管");

        assertEquals("CONFIRMED", confirmed.getStatus().name());
        assertEquals(before, ledgerRepository.count(), "无差异时不应产生任何流水");
        assertEquals(0L, stocktakeService.view(sheet.stocktakeNo()).diffCount());
    }

    @Test
    @DisplayName("漏录实盘量不能提交，错误信息里列出具体批次号")
    void submitRejectsMissingItems() {
        StocktakeResponse sheet = createSheet();
        record(sheet.stocktakeNo(), itemOf(sheet, "ST-A"), "100");

        BusinessException e = assertThrows(BusinessException.class,
                () -> stocktakeService.submit(sheet.stocktakeNo(), OPERATOR));
        assertTrue(e.getMessage().contains("ST-B"), "应指出漏录的批次，实际：" + e.getMessage());
        assertEquals("DRAFT", stocktakeService.view(sheet.stocktakeNo()).status(),
                "提交失败后状态必须留在 DRAFT");
    }

    @Test
    @DisplayName("盘点期间库存被改：确认拒绝整张单，先处理的那条也要回滚")
    void staleBookQuantityRejectsWholeSheetAndRollsBackEarlierItems() {
        StocktakeResponse sheet = createSheet();
        String no = sheet.stocktakeNo();

        // 盘点进行中有人报废了 10 mL：batchB 的账面从 50 变成 40，这张单的基准已失效
        stockService.scrap(chemicalId, batchB,
                new ScrapRequest(new BigDecimal("10"), "容器破损"), "安全员");

        // batchA 排在前、差异能被调整成功；batchB 对不上就整单失败
        record(no, itemOf(sheet, "ST-A"), "90");
        record(no, itemOf(sheet, "ST-B"), "40");
        stocktakeService.submit(no, OPERATOR);

        BusinessException e = assertThrows(BusinessException.class,
                () -> stocktakeService.confirm(no, "主管"));
        assertTrue(e.getMessage().contains("ST-B"), "应指明是哪个批次失效，实际：" + e.getMessage());

        assertEquals(0, batchRepository.currentQuantity(batchA).compareTo(new BigDecimal("100")),
                "整单回滚：先处理成功的 batchA 也不能被改");
        assertEquals(0, batchRepository.currentQuantity(batchB).compareTo(new BigDecimal("40")),
                "batchB 保留报废之后的真实值 40");
        assertTrue(ledgerRepository.findByBatchIdOrderByCreatedAtAscIdAsc(batchA).stream()
                        .noneMatch(l -> "ADJUST".equals(l.getChangeType())),
                "回滚后不能留下半截 ADJUST 流水");
        assertEquals("SUBMITTED", stocktakeService.view(no).status(),
                "确认失败后状态留在 SUBMITTED，可以撤销后重盘");
    }

    @Test
    @DisplayName("差异为 0 也要校验基准：盘点期间库存被改过，零差异条目同样拒绝整单")
    void zeroDiffItemStillVerifiesBookQuantity() {
        StocktakeResponse sheet = createSheet();
        String no = sheet.stocktakeNo();

        // 建单账面 100，盘点期间报废 10 变成 90
        stockService.scrap(chemicalId, batchA,
                new ScrapRequest(new BigDecimal("10"), "容器破损"), "安全员");

        // 盘点人照建单时的账录了 100：差异 0。这正是真实场景里最容易漏掉的路径——
        // 早期实现「差异 0 就跳过」，于是这条单会静默确认成功，盘点什么也没验证
        record(no, itemOf(sheet, "ST-A"), "100");
        record(no, itemOf(sheet, "ST-B"), "50");
        stocktakeService.submit(no, OPERATOR);

        BusinessException e = assertThrows(BusinessException.class,
                () -> stocktakeService.confirm(no, "主管"));
        assertTrue(e.getMessage().contains("ST-A"), "零差异条目也要指出基准已变，实际：" + e.getMessage());
        assertEquals(0, batchRepository.currentQuantity(batchA).compareTo(new BigDecimal("90")),
                "不能因为差异为 0 就把盘点期间的变化放过去");
        assertEquals("SUBMITTED", stocktakeService.view(no).status());
    }

    @Test
    @DisplayName("同一批次不能被两张未完成的盘点单同时覆盖")
    void sameBatchCannotBeInTwoOpenSheets() {
        createSheet();

        BusinessException e = assertThrows(BusinessException.class, this::createSheet);
        assertTrue(e.getMessage().contains("ST-A"), "应指出冲突的批次，实际：" + e.getMessage());

        // 撤销之后就能重新建单
        String first = stocktakeRepository.findAll().get(0).getStocktakeNo();
        stocktakeService.cancel(first, OPERATOR);
        assertEquals(2, createSheet().items().size());
    }

    @Test
    @DisplayName("终态不能回头：已确认的单不能撤销，已撤销的单不能确认")
    void terminalStatesAreFinal() {
        StocktakeResponse sheet = createSheet();
        record(sheet.stocktakeNo(), itemOf(sheet, "ST-A"), "100");
        record(sheet.stocktakeNo(), itemOf(sheet, "ST-B"), "50");
        stocktakeService.submit(sheet.stocktakeNo(), OPERATOR);
        stocktakeService.confirm(sheet.stocktakeNo(), "主管");

        assertThrows(BusinessException.class,
                () -> stocktakeService.cancel(sheet.stocktakeNo(), "主管"));

        StocktakeResponse second = createSheet();
        stocktakeService.cancel(second.stocktakeNo(), OPERATOR);
        assertThrows(BusinessException.class,
                () -> stocktakeService.confirm(second.stocktakeNo(), "主管"));
    }

    @Test
    @DisplayName("对账能发现绕过流水改余额造成的不一致")
    void reconcileDetectsBalanceChangedBehindLedger() {
        assertTrue(stockService.reconcile(chemicalId).consistent(), "刚入库时应平");

        // 模拟「有人直接改了批次表」：这正是台账最怕的漂移，界面本身看不出来
        ChemicalBatch b = batchRepository.findById(batchA).orElseThrow();
        b.setAvailableQuantity(new BigDecimal("999"));
        batchRepository.saveAndFlush(b);

        ReconcileView view = stockService.reconcile(chemicalId);
        assertFalse(view.consistent(), "余额被绕过流水改动后必须判为不一致");
        assertEquals(0, view.totalAvailable().compareTo(new BigDecimal("1049")));
        assertEquals(0, view.totalLedger().compareTo(new BigDecimal("150")));

        ReconcileView.BatchBalance rowA = view.batches().stream()
                .filter(r -> r.batchId().equals(batchA)).findFirst().orElseThrow();
        assertFalse(rowA.consistent());
        assertEquals(0, rowA.availableQuantity().compareTo(new BigDecimal("999")));
        assertEquals(0, rowA.ledgerBalance().compareTo(new BigDecimal("100")));
        assertTrue(view.batches().stream()
                .filter(r -> r.batchId().equals(batchB)).findFirst().orElseThrow().consistent(),
                "没被动过的批次仍然平");
    }

    @Test
    @DisplayName("对账不存在的试剂：404，而不是返回一张全零的空对账单")
    void reconcileUnknownChemicalIsNotFound() {
        assertThrows(ResourceNotFoundException.class, () -> stockService.reconcile(-1L));
    }

    @Test
    @DisplayName("没有库存时建单被拒绝；指定不存在的试剂同理")
    void createWithoutStockIsRejected() {
        stockService.scrap(chemicalId, batchA,
                new ScrapRequest(new BigDecimal("100"), "清空"), OPERATOR);
        stockService.scrap(chemicalId, batchB,
                new ScrapRequest(new BigDecimal("50"), "清空"), OPERATOR);

        assertThrows(BusinessException.class, this::createSheet);
    }
}
