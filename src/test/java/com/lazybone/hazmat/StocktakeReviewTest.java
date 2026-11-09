package com.lazybone.hazmat;

import com.lazybone.hazmat.common.BusinessException;
import com.lazybone.hazmat.dto.ChemicalRequest;
import com.lazybone.hazmat.dto.InboundRequest;
import com.lazybone.hazmat.dto.StocktakeCreateRequest;
import com.lazybone.hazmat.dto.StocktakeItemRequest;
import com.lazybone.hazmat.dto.StocktakeResponse;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 盘点复核档与职责分离。
 *
 * <p>本类把 {@code require-review} 与 {@code require-separate-confirmer} 都打开，
 * 验证「提交 → 复核 → 确认」三步各自的前置条件。默认关闭时的行为由
 * {@link StocktakeTest} 覆盖，两者用独立 H2 库名，互不影响。
 *
 * <p>为什么要单开一个类而不是在一个上下文里切开关：开关是构造器注入的字段，
 * 一个 Spring 上下文只有一份值，测两套行为只能起两个上下文。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:stocktake-review-it;DB_CLOSE_DELAY=-1",
        "hazmat.security.seed-users=false",
        "hazmat.ai.encode-on-startup=false",
        "hazmat.stocktake.require-review=true",
        "hazmat.stocktake.require-separate-confirmer=true"
})
class StocktakeReviewTest {

    private static final String SUBMITTER = "库管员甲";
    private static final String REVIEWER = "复核员乙";
    private static final String CONFIRMER = "主管丙";

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

    @BeforeEach
    void setUp() {
        cleanUp();
        chemicalId = chemicalService.create(new ChemicalRequest(
                "CAS-STR-001", "复核测试试剂", "易燃", "中", "mL", "AR 500mL", null, null)).getId();
        stockService.inbound(chemicalId,
                new InboundRequest("STR-A", new BigDecimal("100"), "柜 A-1",
                        LocalDate.now().plusYears(1)), SUBMITTER);
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

    /** 建单、录入、提交，停在 SUBMITTED */
    private String submittedSheet() {
        Stocktake st = stocktakeService.create(
                new StocktakeCreateRequest(List.of(chemicalId), "复核流程测试"), SUBMITTER);
        StocktakeResponse sheet = stocktakeService.view(st.getStocktakeNo());
        stocktakeService.recordActual(sheet.stocktakeNo(), sheet.items().get(0).itemId(),
                new StocktakeItemRequest(new BigDecimal("100"), null));
        stocktakeService.submit(sheet.stocktakeNo(), SUBMITTER);
        return sheet.stocktakeNo();
    }

    @Test
    @DisplayName("要求复核时，SUBMITTED 直接确认被拒，并说明要先复核")
    void confirmRequiresReviewWhenSwitchOn() {
        String no = submittedSheet();

        BusinessException e = assertThrows(BusinessException.class,
                () -> stocktakeService.confirm(no, CONFIRMER));
        assertTrue(e.getMessage().contains("先复核"), "实际：" + e.getMessage());
        assertEquals("SUBMITTED", stocktakeService.view(no).status(), "被拒后状态不能推进");
    }

    @Test
    @DisplayName("提交人不能复核自己的单，复核被拒后状态仍是 SUBMITTED")
    void submitterCannotReviewOwnSheet() {
        String no = submittedSheet();

        BusinessException e = assertThrows(BusinessException.class,
                () -> stocktakeService.review(no, SUBMITTER, "自己看一眼"));
        assertTrue(e.getMessage().contains("复核人不能是提交人"), "实际：" + e.getMessage());
        assertEquals("SUBMITTED", stocktakeService.view(no).status());
    }

    @Test
    @DisplayName("另一人复核后状态为 REVIEWED，字段留痕；随后可由第三人确认调账")
    void anotherPersonCanReviewThenConfirm() {
        String no = submittedSheet();

        stocktakeService.review(no, REVIEWER, "数量和差异都看过了");
        StocktakeResponse reviewed = stocktakeService.view(no);
        assertEquals("REVIEWED", reviewed.status());
        assertEquals(REVIEWER, reviewed.reviewedBy());
        assertNotNull(reviewed.reviewedAt());
        assertEquals("数量和差异都看过了", reviewed.reviewComment());

        Stocktake confirmed = stocktakeService.confirm(no, CONFIRMER);
        assertEquals("CONFIRMED", confirmed.getStatus().name());
        assertEquals(CONFIRMER, confirmed.getConfirmedBy());
    }

    @Test
    @DisplayName("提交人不能确认自己的单，即使已经有人复核过")
    void submitterCannotConfirmOwnSheet() {
        String no = submittedSheet();
        stocktakeService.review(no, REVIEWER, null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> stocktakeService.confirm(no, SUBMITTER));
        assertTrue(e.getMessage().contains("确认人不能是提交人"), "实际：" + e.getMessage());
        assertEquals("REVIEWED", stocktakeService.view(no).status());
    }

    @Test
    @DisplayName("复核只能从 SUBMITTED 进入：DRAFT 和 REVIEWED 都不行")
    void reviewOnlyFromSubmitted() {
        Stocktake st = stocktakeService.create(
                new StocktakeCreateRequest(List.of(chemicalId), "未提交的单"), SUBMITTER);
        assertThrows(BusinessException.class,
                () -> stocktakeService.review(st.getStocktakeNo(), REVIEWER, null),
                "草稿状态还没有实盘数据，复核没有意义");
        // 同一批次不能同时挂两张未完成的单；这张草稿先撤掉再建下一张
        stocktakeService.cancel(st.getStocktakeNo(), SUBMITTER);

        String no = submittedSheet();
        stocktakeService.review(no, REVIEWER, null);
        assertThrows(BusinessException.class,
                () -> stocktakeService.review(no, CONFIRMER, "再看一遍"),
                "REVIEWED 不能再次复核，否则签字人会被后一次覆盖");
    }

    @Test
    @DisplayName("复核之后仍可撤销：不调账，库存保持原值")
    void reviewedSheetCanStillBeCancelled() {
        String no = submittedSheet();
        stocktakeService.review(no, REVIEWER, null);

        stocktakeService.cancel(no, CONFIRMER);
        assertEquals("CANCELLED", stocktakeService.view(no).status());
        assertEquals(0, batchRepository.sumAvailable(chemicalId).compareTo(new BigDecimal("100")),
                "撤销不调账，库存不能变");
    }
}
