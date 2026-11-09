package com.lazybone.hazmat.ops;

import com.lazybone.hazmat.dto.ChemicalRequest;
import com.lazybone.hazmat.entity.AlertSeverity;
import com.lazybone.hazmat.entity.ChemicalBatch;
import com.lazybone.hazmat.entity.OpsAlert;
import com.lazybone.hazmat.entity.Stocktake;
import com.lazybone.hazmat.entity.StocktakeStatus;
import com.lazybone.hazmat.repository.ChemicalBatchRepository;
import com.lazybone.hazmat.repository.ChemicalRepository;
import com.lazybone.hazmat.repository.OpsAlertRepository;
import com.lazybone.hazmat.repository.StocktakeRepository;
import com.lazybone.hazmat.service.ChemicalService;
import com.lazybone.hazmat.service.StockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运维巡检与告警的行为测试。
 *
 * <p>直接调用 job 方法而不是等 cron：断言要在确定的时刻、对确定的数据做，
 * 等调度器触发会让用例依赖运行时长。方法体本身和 {@code @Scheduled} 调的是同一个，
 * 差别只在于谁触发。
 *
 * <p>带 {@code hazmat.jobs.enabled=false}：job Bean 不装配，这里手工 new 一个，
 * 后台调度线程不会在这一类用例里插入额外的告警行。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ops-jobs;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "hazmat.ai.encode-on-startup=false",
        "hazmat.jobs.enabled=false"
})
class OpsJobsTest {

    @Autowired private AlertService alertService;
    @Autowired private OpsAlertRepository alertRepository;
    @Autowired private ChemicalService chemicalService;
    @Autowired private ChemicalRepository chemicalRepository;
    @Autowired private ChemicalBatchRepository batchRepository;
    @Autowired private StocktakeRepository stocktakeRepository;
    @Autowired private StockService stockService;

    private StockOpsJob job;

    @BeforeEach
    void setUp() {
        alertRepository.deleteAll();
        stocktakeRepository.deleteAll();
        job = new StockOpsJob(batchRepository, chemicalRepository, stocktakeRepository,
                stockService, alertService,
                new com.lazybone.hazmat.config.OpsProperties());
    }

    @Test
    @DisplayName("同一指纹重复上报只累加次数，解决后再次发生会重新打开")
    void alertDeduplicatesAndReopensAfterResolve() {
        alertService.raise("EXPIRY", AlertSeverity.WARN, "EXPIRY:TEST", "第一次");
        alertService.raise("EXPIRY", AlertSeverity.WARN, "EXPIRY:TEST", "第二次");

        List<OpsAlert> alerts = alertRepository.findAll();
        assertThat(alerts).hasSize(1);
        OpsAlert open = alerts.get(0);
        assertThat(open.getOccurrences()).isEqualTo(2);
        assertThat(open.getMessage()).isEqualTo("第二次");
        assertThat(open.getResolvedAt()).isNull();

        alertService.resolve(open.getId(), "admin");
        assertThat(alertRepository.findById(open.getId()).orElseThrow().getResolvedAt()).isNotNull();

        // 同一次故障再次被扫到：必须复用原来那行并重新打开，
        // 否则「已解决的 fingerprint」会把后续告警永远挡在唯一索引外面
        alertService.raise("EXPIRY", AlertSeverity.WARN, "EXPIRY:TEST", "第三次");
        assertThat(alertRepository.findAll()).hasSize(1);
        OpsAlert reopened = alertRepository.findById(open.getId()).orElseThrow();
        assertThat(reopened.getResolvedAt()).isNull();
        assertThat(reopened.getResolvedBy()).isNull();
        assertThat(reopened.getOccurrences()).isEqualTo(3);
    }

    @Test
    @DisplayName("效期扫描把多个临期批次汇总成一条告警，同一天重复扫描只累加")
    void expiryScanAggregatesBatchesIntoOneAlert() {
        Long chemicalId = chemicalService.create(new ChemicalRequest(
                "CAS-OPS-EXP-1", "临期试剂", "易燃", "中", "mL", "AR 500mL", null, null)).getId();
        batchRepository.save(ChemicalBatch.builder()
                .chemicalId(chemicalId).batchNo("B-OPS-EXP-1")
                .initialQuantity(new BigDecimal("10")).availableQuantity(new BigDecimal("10"))
                .location("柜 1").expireDate(LocalDate.now().plusDays(3)).build());
        batchRepository.save(ChemicalBatch.builder()
                .chemicalId(chemicalId).batchNo("B-OPS-EXP-2")
                .initialQuantity(new BigDecimal("20")).availableQuantity(new BigDecimal("20"))
                .location("柜 2").expireDate(LocalDate.now().plusDays(5)).build());

        job.scanExpiring();
        job.scanExpiring();

        List<OpsAlert> alerts = alertRepository.findAll();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getAlertType()).isEqualTo("EXPIRY");
        assertThat(alerts.get(0).getFingerprint()).isEqualTo("EXPIRY:" + LocalDate.now().plusDays(30));
        assertThat(alerts.get(0).getMessage()).contains("2 个批次");
        assertThat(alerts.get(0).getOccurrences()).isEqualTo(2);
    }

    @Test
    @DisplayName("盘点提醒按状态区分待复核与待确认，不会把 REVIEWED 漏掉")
    void stocktakeReminderDistinguishesReviewAndConfirm() {
        LocalDateTime stale = LocalDateTime.now().minusHours(48);
        stocktakeRepository.save(Stocktake.builder()
                .stocktakeNo("ST-OPS-1").status(StocktakeStatus.SUBMITTED)
                .createdBy("alice").submittedBy("alice").submittedAt(stale).build());
        stocktakeRepository.save(Stocktake.builder()
                .stocktakeNo("ST-OPS-2").status(StocktakeStatus.REVIEWED)
                .createdBy("alice").submittedBy("alice").submittedAt(stale)
                .reviewedBy("admin").reviewedAt(stale).build());
        // 未超时的不该被提醒
        stocktakeRepository.save(Stocktake.builder()
                .stocktakeNo("ST-OPS-3").status(StocktakeStatus.SUBMITTED)
                .createdBy("alice").submittedBy("alice")
                .submittedAt(LocalDateTime.now().minusHours(1)).build());

        job.remindStocktakes();

        List<OpsAlert> alerts = alertRepository.findAll();
        assertThat(alerts).extracting(OpsAlert::getFingerprint)
                .containsExactlyInAnyOrder(
                        "STOCKTAKE_REVIEW_STALE:ST-OPS-1",
                        "STOCKTAKE_CONFIRM_STALE:ST-OPS-2");
        assertThat(alerts).extracting(OpsAlert::getAlertType)
                .contains("STOCKTAKE_REVIEW_STALE", "STOCKTAKE_CONFIRM_STALE");
    }

    @Test
    @DisplayName("对账巡检把不平的试剂写成 CRITICAL 告警")
    void reconcileScanRaisesCriticalAlertForInconsistentChemical() {
        Long chemicalId = chemicalService.create(new ChemicalRequest(
                "CAS-OPS-REC-1", "对账不平的试剂", "易燃", "中", "mL", "AR 500mL", null, null)).getId();
        // 有批次、没有流水：reconcile 判定为不一致（入库必写流水，缺流水说明来路不明）
        batchRepository.save(ChemicalBatch.builder()
                .chemicalId(chemicalId).batchNo("B-OPS-REC-1")
                .initialQuantity(new BigDecimal("5")).availableQuantity(new BigDecimal("5"))
                .location("柜 1").expireDate(LocalDate.now().plusMonths(6)).build());

        job.reconcileAll();

        assertThat(alertRepository.findAll())
                .filteredOn(alert -> alert.getFingerprint().equals("RECONCILE:" + chemicalId))
                .singleElement()
                .satisfies(alert -> {
                    assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.CRITICAL);
                    assertThat(alert.getMessage()).contains("对账不平");
                });
    }
}
