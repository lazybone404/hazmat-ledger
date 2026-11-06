package com.lazybone.hazmat.msds.mq;

import com.lazybone.hazmat.msds.MsdsVectorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MQ 关闭时的编码入口：保持项目原来的进程内单线程行为。
 *
 * <p>这是默认路径，测试和裸 H2 启动都走它，不需要任何 broker。
 * 队列满时仍然按原策略丢弃触发（下次全量扫描会补），这是已知的降级边界；
 * 需要跨重启、跨实例的可靠性时把 {@code hazmat.mq.enabled} 打开。
 */
@Component
@ConditionalOnProperty(prefix = "hazmat.mq", name = "enabled",
        havingValue = "false", matchIfMissing = true)
public class LocalEncodeTaskDispatcher implements EncodeTaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LocalEncodeTaskDispatcher.class);

    private final MsdsVectorService vectorService;

    public LocalEncodeTaskDispatcher(MsdsVectorService vectorService) {
        this.vectorService = vectorService;
    }

    @Override
    public EncodeTaskView requestEncoding(String indexVersion, String reason) {
        log.debug("触发本地 MSDS 编码: reason={}, indexVersion={}", reason, indexVersion);
        vectorService.scheduleEncoding();
        return null;
    }
}
