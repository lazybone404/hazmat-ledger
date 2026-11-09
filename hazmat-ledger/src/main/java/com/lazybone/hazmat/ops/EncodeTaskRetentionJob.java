package com.lazybone.hazmat.ops;

import com.lazybone.hazmat.config.OpsProperties;
import com.lazybone.hazmat.msds.mq.EncodeTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 编码任务保留清理：只删 DONE / DEAD 且完成时间早于保留期的行。
 *
 * <p>PENDING / RUNNING / FAILED 都不删：前两者还在流程里，FAILED 是等待重试的证据。
 * 任务表既是状态机的载体也是排查材料，清理范围必须窄。
 */
@Component
@ConditionalOnProperty(prefix = "hazmat.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EncodeTaskRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(EncodeTaskRetentionJob.class);

    private final EncodeTaskService encodeTaskService;
    private final OpsProperties properties;

    public EncodeTaskRetentionJob(EncodeTaskService encodeTaskService, OpsProperties properties) {
        this.encodeTaskService = encodeTaskService;
        this.properties = properties;
    }

    @Scheduled(cron = "${hazmat.ops.encode-task-retention-cron}")
    public void purgeFinished() {
        try {
            int days = Math.max(1, properties.getOps().getEncodeTaskRetentionDays());
            LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
            int deleted = encodeTaskService.purgeFinishedBefore(cutoff);
            log.info("编码任务清理完成: cutoff={}, deleted={}", cutoff, deleted);
        } catch (RuntimeException e) {
            log.error("编码任务清理失败，本轮跳过", e);
        }
    }
}
