package com.lazybone.hazmat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 定时任务与运维巡检配置。
 *
 * <p>{@code hazmat.jobs.enabled} 是总开关：关闭时所有 {@code @Scheduled} Bean 都不装配。
 * 默认套件在 surefire 里关掉它，避免后台线程和测试断言互相干扰。
 */
@Data
@Component
@ConfigurationProperties(prefix = "hazmat")
public class OpsProperties {

    private Jobs jobs = new Jobs();
    private Ops ops = new Ops();

    @Data
    public static class Jobs {
        private boolean enabled = true;
    }

    @Data
    public static class Ops {
        /** 效期扫描 cron，默认每天 08:00 */
        private String expiryCron = "0 0 8 * * *";
        /** 对账巡检 cron，默认每天 02:30 */
        private String reconcileCron = "0 30 2 * * *";
        /** 盘点超时提醒 cron，默认每天 09:00 */
        private String stocktakeReminderCron = "0 0 9 * * *";
        /** 效期预警窗口，默认 30 天 */
        private int expiryDays = 30;
        /** 盘点提交/复核后超过多少小时仍未推进就提醒 */
        private int stocktakeStaleHours = 24;
        /** 编码任务终态保留天数 */
        private int encodeTaskRetentionDays = 7;
        /** 每轮对账最多处理多少个试剂，避免一次扫全库 */
        private int reconcileMaxChemicals = 100;
    }
}
