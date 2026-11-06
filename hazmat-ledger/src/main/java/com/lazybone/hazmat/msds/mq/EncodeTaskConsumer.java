package com.lazybone.hazmat.msds.mq;

import com.lazybone.hazmat.dto.MsdsIndexStatus;
import com.lazybone.hazmat.entity.MsdsEncodeTask;
import com.lazybone.hazmat.msds.MsdsVectorService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.Objects;
import java.util.Optional;

/**
 * 编码任务消费者：claim 成功后执行一轮编码，再按结果迁移任务状态。
 *
 * <p>重复投递是常态（至少一次语义、relay 补发、实例崩溃后的重投），
 * 所以第一步永远是条件 UPDATE claim：抢不到就直接 ack，不再调用模型。
 *
 * <p>失败时先把任务标成 FAILED 再抛异常，让 Spring Retry 的下一次尝试
 * 能重新 claim；否则任务停在 RUNNING，租约未过期前谁也接不了。
 */
@Component
@ConditionalOnProperty(prefix = "hazmat.mq", name = "enabled", havingValue = "true")
public class EncodeTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(EncodeTaskConsumer.class);

    private final EncodeTaskService taskService;
    private final MsdsVectorService vectorService;
    private final MeterRegistry meterRegistry;
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName();

    public EncodeTaskConsumer(EncodeTaskService taskService,
                              MsdsVectorService vectorService,
                              MeterRegistry meterRegistry) {
        this.taskService = taskService;
        this.vectorService = vectorService;
        this.meterRegistry = meterRegistry;
    }

    @RabbitListener(queues = "${hazmat.mq.encode.queue:hazmat.msds.encode}")
    public void handle(EncodeTaskMessage message) {
        if (message == null || message.taskId() == null) {
            log.warn("收到没有 taskId 的编码消息，直接丢弃");
            return;
        }
        Long taskId = message.taskId();
        Optional<MsdsEncodeTask> claimed = taskService.claim(taskId, workerId);
        if (claimed.isEmpty()) {
            // 重复消息、已被别的实例抢走，或者重试次数已经耗尽：都不再执行
            log.debug("编码任务未抢到，按重复消息处理: id={}", taskId);
            return;
        }

        MsdsEncodeTask task = claimed.get();
        String activeVersion = vectorService.activeVersion();
        if (!Objects.equals(task.getIndexVersion(), activeVersion)) {
            taskService.markSkipped(taskId,
                    "索引版本已切换：任务 " + task.getIndexVersion() + "，当前 " + activeVersion);
            return;
        }

        log.info("开始处理 MSDS 编码任务: id={}, attempt={}/{}, indexVersion={}",
                taskId, task.getAttempts(), task.getMaxAttempts(), task.getIndexVersion());

        MsdsVectorService.EncodeResult result;
        try {
            result = vectorService.encodePending(task.getIndexVersion(), 0);
        } catch (RuntimeException e) {
            taskService.markFailed(taskId, "编码执行异常: " + e.getMessage());
            throw e;
        }

        if (result.paused()) {
            taskService.markDead(taskId, "编码已持久化暂停: " + vectorService.pausedReason());
            return;
        }
        if (result.failed() > 0) {
            String reason = "本轮编码失败 " + result.failed() + "/" + result.attempted() + " 块";
            taskService.markFailed(taskId, reason);
            throw new IllegalStateException(reason);
        }
        if (vectorService.needsRebuild()) {
            taskService.markDead(taskId, "索引元数据与当前配置不一致，需人工 reindex");
            return;
        }

        // 编码方法只返回本轮结果；「是否还有块没编码」要以状态接口为准。
        // 例如某个块达到单块失败上限后会被跳过，这一轮可能一块都没尝试，但索引并未完成。
        MsdsIndexStatus status = vectorService.status();
        if (status.failed() > 0) {
            taskService.markDead(taskId,
                    "有 " + status.failed() + " 块达到单块失败上限，需人工 reindex");
            return;
        }
        if (status.pending() > 0) {
            if (vectorService.isEncoding()) {
                taskService.markFailed(taskId, "另一个编码任务正在运行，稍后重试");
                throw new IllegalStateException("另一个编码任务正在运行");
            }
            taskService.markFailed(taskId, "仍有 " + status.pending() + " 块待编码");
            throw new IllegalStateException("仍有 " + status.pending() + " 块待编码");
        }

        taskService.markDone(taskId);
        meterRegistry.counter("msds.encode.task", "event", "processed").increment();
    }
}
