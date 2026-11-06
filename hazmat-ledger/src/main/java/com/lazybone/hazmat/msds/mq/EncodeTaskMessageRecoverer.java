package com.lazybone.hazmat.msds.mq;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;

/**
 * 重试耗尽后的收口：任务转 DEAD，消息拒绝并进死信队列。
 *
 * <p>为什么 recoverer 要主动抛 {@link AmqpRejectAndDontRequeueException}：
 * 如果只是标记 DEAD 然后正常返回，容器会 ack 这条消息，队列里就没有证据了；
 * 抛这个异常让容器按 {@code default-requeue-rejected=false} 拒绝，消息才会走 DLX。
 *
 * <p>标记 DEAD 本身不能失败：数据库写不进去时也要把消息送进 DLQ，
 * 否则一条坏消息会在主队列里反复重试，把消费线程占死。
 */
public class EncodeTaskMessageRecoverer implements MessageRecoverer {

    private static final Logger log = LoggerFactory.getLogger(EncodeTaskMessageRecoverer.class);

    private final EncodeTaskService taskService;
    private final MeterRegistry meterRegistry;

    public EncodeTaskMessageRecoverer(EncodeTaskService taskService,
                                      MeterRegistry meterRegistry) {
        this.taskService = taskService;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void recover(Message message, Throwable cause) {
        Long taskId = extractTaskId(message);
        String reason = cause == null || cause.getMessage() == null
                ? "重试次数耗尽"
                : "重试次数耗尽: " + cause.getMessage();
        if (taskId != null) {
            try {
                taskService.markDead(taskId, reason);
            } catch (RuntimeException e) {
                // 数据库不可用时仍然要让消息进 DLQ，任务状态留给运维修复后手工处理
                log.error("重试耗尽后标记任务 DEAD 失败: id={}, reason={}", taskId, e.getMessage());
            }
        }
        meterRegistry.counter("msds.encode.task", "event", "dead").increment();
        log.error("MSDS 编码任务重试耗尽，转入死信队列: id={}, reason={}", taskId, reason);
        throw new AmqpRejectAndDontRequeueException(reason, cause);
    }

    private static Long extractTaskId(Message message) {
        Object header = message.getMessageProperties()
                .getHeader(EncodeTaskMessage.TASK_ID_HEADER);
        if (header instanceof Number number) {
            return number.longValue();
        }
        if (header instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                // 继续尝试消息体
            }
        }
        return null;
    }
}
