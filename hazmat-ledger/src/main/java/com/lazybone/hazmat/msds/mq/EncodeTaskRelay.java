package com.lazybone.hazmat.msds.mq;

import com.lazybone.hazmat.entity.MsdsEncodeTask;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时补偿：把「任务已落库但消息没送到」和「任务卡在 RUNNING」的情况收口。
 *
 * <p>它解决的是先写库后发消息之间那段不可避免的窗口：发送失败、broker 停机、
 * 实例在消费前崩溃，消息都不会自己回来。relay 按任务表重发，重复消息由 claim 去重。
 *
 * <p>不处理业务失败的重试——那是消费端 Spring Retry 的事。这里只负责「把该有的消息补上」。
 */
@Component
@ConditionalOnProperty(prefix = "hazmat.mq", name = "enabled", havingValue = "true")
public class EncodeTaskRelay {

    private static final Logger log = LoggerFactory.getLogger(EncodeTaskRelay.class);

    private final EncodeTaskService taskService;
    private final RabbitEncodeTaskDispatcher dispatcher;
    private final MsdsMqProperties properties;
    private final MeterRegistry meterRegistry;

    public EncodeTaskRelay(EncodeTaskService taskService,
                           RabbitEncodeTaskDispatcher dispatcher,
                           MsdsMqProperties properties,
                           MeterRegistry meterRegistry) {
        this.taskService = taskService;
        this.dispatcher = dispatcher;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${hazmat.mq.encode.relay-interval-ms:30000}",
            initialDelayString = "${hazmat.mq.encode.relay-interval-ms:30000}")
    public void relay() {
        try {
            LocalDateTime now = LocalDateTime.now();
            List<MsdsEncodeTask> candidates = taskService.findRelayCandidates(
                    now, properties.getEncode().getRelayBatchSize());
            if (candidates.isEmpty()) {
                return;
            }
            for (MsdsEncodeTask task : candidates) {
                if (task.getAttempts() >= task.getMaxAttempts()) {
                    // claim 已经不可能成功，继续重发只会制造死循环消息
                    taskService.markDead(task.getId(), "重试次数已耗尽，relay 停止补发");
                    continue;
                }
                dispatcher.publish(task.getId());
                meterRegistry.counter("msds.encode.task", "event", "relayed").increment();
                log.info("relay 补发编码任务: id={}, status={}, attempts={}/{}",
                        task.getId(), task.getStatus(), task.getAttempts(), task.getMaxAttempts());
            }
        } catch (RuntimeException e) {
            // 定时任务不能因为一次数据库抖动就停掉；下一轮继续
            log.warn("MSDS 编码任务 relay 本轮失败: {}", e.getMessage());
        }
    }
}
