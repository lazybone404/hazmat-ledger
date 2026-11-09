package com.lazybone.hazmat.ops;

import com.lazybone.hazmat.config.OpsProperties;
import com.lazybone.hazmat.entity.AlertSeverity;
import com.lazybone.hazmat.entity.Chemical;
import com.lazybone.hazmat.entity.ChemicalBatch;
import com.lazybone.hazmat.entity.Stocktake;
import com.lazybone.hazmat.entity.StocktakeStatus;
import com.lazybone.hazmat.dto.ReconcileView;
import com.lazybone.hazmat.repository.ChemicalBatchRepository;
import com.lazybone.hazmat.repository.ChemicalRepository;
import com.lazybone.hazmat.repository.StocktakeRepository;
import com.lazybone.hazmat.service.StockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 库存相关的运维巡检：效期扫描、对账巡检、盘点超时提醒。
 *
 * <p>三个任务共用一条原则：<b>异常只记日志，不往外抛</b>。{@code @Scheduled} 的方法
 * 抛异常不会让调度线程退出，但会让这一轮后面的逻辑全部跳过；单独 try 住每个任务，
 * 一个任务坏了不影响另外两个。
 *
 * <p>三个任务都没有做 leader election：多实例会各跑一遍，写入靠
 * {@link AlertService} 的 fingerprint 去重收敛成一行。代价是 {@code occurrences}
 * 会比单实例多算，换来的是不需要额外组件。
 */
@Component
@ConditionalOnProperty(prefix = "hazmat.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StockOpsJob {

    private static final Logger log = LoggerFactory.getLogger(StockOpsJob.class);

    private final ChemicalBatchRepository batchRepository;
    private final ChemicalRepository chemicalRepository;
    private final StocktakeRepository stocktakeRepository;
    private final StockService stockService;
    private final AlertService alertService;
    private final OpsProperties properties;

    public StockOpsJob(ChemicalBatchRepository batchRepository,
                        ChemicalRepository chemicalRepository,
                       StocktakeRepository stocktakeRepository,
                       StockService stockService,
                       AlertService alertService,
                       OpsProperties properties) {
        this.batchRepository = batchRepository;
        this.chemicalRepository = chemicalRepository;
        this.stocktakeRepository = stocktakeRepository;
        this.stockService = stockService;
        this.alertService = alertService;
        this.properties = properties;
    }

    /**
     * 效期扫描：把「未来 N 天内到可用期且还有余量」的批次汇总成一条告警。
     *
     * <p>按日期做 fingerprint（{@code EXPIRY:2026-12-09}）：同一天重复跑只累加次数，
     * 第二天自然换一条新指纹。不按批次逐条报，是因为一个柜子里几十瓶同时临期时，
     * 几十行告警会把真正需要处理的对账问题挤下去。
     */
    @Scheduled(cron = "${hazmat.ops.expiry-cron}")
    public void scanExpiring() {
        try {
            LocalDate threshold = LocalDate.now().plusDays(properties.getOps().getExpiryDays());
            Page<ChemicalBatch> page = batchRepository.findExpiring(threshold, PageRequest.of(0, 1));
            long total = page.getTotalElements();
            if (total > 0) {
                alertService.raise("EXPIRY", AlertSeverity.WARN, "EXPIRY:" + threshold,
                        "有 " + total + " 个批次将在 " + threshold + " 前到可用期，请安排使用或报废");
            }
            log.info("效期扫描完成: threshold={}, batches={}", threshold, total);
        } catch (RuntimeException e) {
            log.error("效期扫描失败，本轮跳过", e);
        }
    }

    /**
     * 对账巡检：逐个试剂调用 {@link StockService#reconcile}，不平的写 CRITICAL 告警。
     *
     * <p>每轮最多处理 {@code reconcile-max-chemicals} 个，按 id 升序而不是随机：
     * 固定顺序至少保证「每次扫到的都是同一批」，不会出现某个试剂永远排不到。
     * 试剂总数超过上限时，剩下的下一轮从同一位置开始——这是当前实现的已知局限，
     * 数据量真到需要轮转时再补游标。
     */
    @Scheduled(cron = "${hazmat.ops.reconcile-cron}")
    public void reconcileAll() {
        try {
            int max = Math.max(1, properties.getOps().getReconcileMaxChemicals());
            List<Chemical> chemicals = chemicalRepository
                    .findAll(PageRequest.of(0, max, org.springframework.data.domain.Sort.by("id")))
                    .getContent();
            int inconsistent = 0;
            for (Chemical chemical : chemicals) {
                ReconcileView view = stockService.reconcile(chemical.getId());
                if (!view.consistent()) {
                    inconsistent++;
                    alertService.raise("RECONCILE", AlertSeverity.CRITICAL,
                            "RECONCILE:" + chemical.getId(),
                            "对账不平: " + chemical.getName() + "（id=" + chemical.getId()
                                    + "），批次合计 " + view.totalAvailable()
                                    + "，流水累计 " + view.totalLedger() + " " + chemical.getUnit());
                }
            }
            log.info("对账巡检完成: checked={}, inconsistent={}", chemicals.size(), inconsistent);
        } catch (RuntimeException e) {
            log.error("对账巡检失败，本轮跳过", e);
        }
    }

    /**
     * 盘点超时提醒：提交或复核后超过 N 小时没有推进的单据。
     *
     * <p>按状态区分指纹与文案：{@code STOCKTAKE_REVIEW_STALE} 表示「已提交待复核」，
     * {@code STOCKTAKE_CONFIRM_STALE} 表示「已复核待确认」。只查 SUBMITTED 会漏掉
     * 卡在复核之后的单子——那正是需要有人去点确认的状态。
     */
    @Scheduled(cron = "${hazmat.ops.stocktake-reminder-cron}")
    public void remindStocktakes() {
        try {
            int staleHours = Math.max(1, properties.getOps().getStocktakeStaleHours());
            LocalDateTime cutoff = LocalDateTime.now().minusHours(staleHours);
            List<Stocktake> stale = stocktakeRepository.findByStatusInAndSubmittedAtBefore(
                    List.of(StocktakeStatus.SUBMITTED, StocktakeStatus.REVIEWED), cutoff);
            for (Stocktake sheet : stale) {
                boolean awaitingConfirm = sheet.getStatus() == StocktakeStatus.REVIEWED;
                String type = awaitingConfirm ? "STOCKTAKE_CONFIRM_STALE" : "STOCKTAKE_REVIEW_STALE";
                String action = awaitingConfirm ? "已复核待确认" : "已提交待复核";
                alertService.raise(type, AlertSeverity.WARN,
                        type + ":" + sheet.getStocktakeNo(),
                        "盘点单 " + sheet.getStocktakeNo() + " " + action + "超过 "
                                + staleHours + " 小时，提交人 " + sheet.getSubmittedBy());
            }
            log.info("盘点超时提醒完成: stale={}", stale.size());
        } catch (RuntimeException e) {
            log.error("盘点超时提醒失败，本轮跳过", e);
        }
    }
}
