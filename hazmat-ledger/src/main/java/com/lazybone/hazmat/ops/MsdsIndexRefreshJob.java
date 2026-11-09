package com.lazybone.hazmat.ops;

import com.lazybone.hazmat.msds.MsdsIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定期检查其他实例是否切换了索引版本，切换了就重建本实例的内存字面索引。
 *
 * <p>为什么需要：内存索引是进程内的，实例 A 重建并切换 active 版本后，实例 B 的
 * {@code MsdsLiteralIndex} 还停在旧版本——表现是同一个检索请求打到不同实例结果不同，
 * 而且旧版本被下一次重建清理后，B 还会继续召回已经不存在的块。
 *
 * <p>为什么用 {@code fixedDelay} 而不是 cron：这是「追平延迟」而不是「定点任务」，
 * 固定间隔的延迟更好估——重启后 30 秒内一定对齐，不受整点附近的调度抖动影响。
 * 单次刷新是几毫秒级的版本号查询，没有变化时立即返回，不做全表扫描。
 */
@Component
@ConditionalOnProperty(prefix = "hazmat.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MsdsIndexRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(MsdsIndexRefreshJob.class);

    private final MsdsIndexService indexService;

    public MsdsIndexRefreshJob(MsdsIndexService indexService) {
        this.indexService = indexService;
    }

    @Scheduled(fixedDelayString = "${hazmat.msds.index-refresh-ms:30000}")
    public void refreshIfChanged() {
        try {
            indexService.refreshIfVersionChanged();
        } catch (RuntimeException e) {
            // 刷新失败只是继续用旧索引，下一轮会再试；不能让调度线程因为一次数据库抖动就停摆
            log.error("MSDS 索引版本检查失败，继续使用当前内存索引", e);
        }
    }
}
