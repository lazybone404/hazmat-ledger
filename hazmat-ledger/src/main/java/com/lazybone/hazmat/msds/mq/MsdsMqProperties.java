package com.lazybone.hazmat.msds.mq;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MSDS 编码任务队列配置。
 *
 * <p>默认关闭：默认套件和裸 H2 启动不需要 RabbitMQ，编码仍走
 * {@link com.lazybone.hazmat.msds.MsdsVectorService} 里的单线程 executor。
 * Docker Compose 默认打开，但 broker 起不来也不阻止应用启动——
 * 任务留在 PENDING，relay 在 broker 恢复后补发。
 */
@Data
@Component
@ConfigurationProperties(prefix = "hazmat.mq")
public class MsdsMqProperties {

    /** 是否启用 RabbitMQ 编码任务队列 */
    private boolean enabled = false;

    private Encode encode = new Encode();

    @Data
    public static class Encode {

        private String exchange = "hazmat.msds";

        private String queue = "hazmat.msds.encode";

        private String routingKey = "msds.encode";

        private String dlx = "hazmat.msds.dlx";

        private String dlq = "hazmat.msds.encode.dlq";

        private String dlqRoutingKey = "msds.encode.dlq";

        /** 单个任务最多被 claim 几次（含首次）。耗尽后转 DEAD 并进死信队列 */
        private int maxAttempts = 3;

        private long retryInitialIntervalMs = 2000;

        private double retryMultiplier = 2.0;

        private long retryMaxIntervalMs = 10000;

        /** RUNNING 超过这个时间没有推进，允许其他实例接管 */
        private long claimLeaseMs = 600_000;

        private long relayIntervalMs = 30_000;

        /** PENDING / FAILED 至少静置这么久才补发，避免和正常消费抢跑 */
        private long relayMinAgeMs = 10_000;

        private int relayBatchSize = 50;
    }
}
