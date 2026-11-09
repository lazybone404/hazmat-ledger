package com.lazybone.hazmat.msds.mq;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 重试耗尽后的收口：标记 DEAD 并让消息进死信队列。
 *
 * <p>两个断言都不能省：只标记 DEAD 而不抛异常，容器会 ack 消息，DLQ 里就没有证据；
 * 只抛异常而不标记，任务会永远停在 RUNNING 占着租约。
 */
class EncodeTaskMessageRecovererTest {

    private final EncodeTaskService taskService = mock(EncodeTaskService.class);
    private final EncodeTaskMessageRecoverer recoverer =
            new EncodeTaskMessageRecoverer(taskService, new SimpleMeterRegistry());

    @Test
    @DisplayName("带 taskId 的消息：标记 DEAD 后仍抛拒绝异常")
    void marksDeadAndRejects() {
        Message message = message(42L, "boom");

        assertThatThrownBy(() -> recoverer.recover(message, new IllegalStateException("重试 3 次仍失败")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                // 原因要带失败摘要，排查 DLQ 里的消息时才有线索
                .hasMessageContaining("重试 3 次仍失败");

        verify(taskService).markDead(42L, "重试次数耗尽: 重试 3 次仍失败");
    }

    @Test
    @DisplayName("没有 taskId 的消息只进 DLQ，不碰数据库")
    void withoutTaskIdDoesNotTouchDatabase() {
        Message message = new Message("{}".getBytes(), new MessageProperties());

        assertThatThrownBy(() -> recoverer.recover(message, new IllegalStateException("bad")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verifyNoInteractions(taskService);
    }

    @Test
    @DisplayName("标记 DEAD 失败时仍然把消息送进 DLQ")
    void databaseFailureStillRejects() {
        Message message = message(7L, "boom");
        org.mockito.Mockito.doThrow(new IllegalStateException("数据库不可用"))
                .when(taskService).markDead(7L, "重试次数耗尽: boom");

        assertThatThrownBy(() -> recoverer.recover(message, new IllegalStateException("boom")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(taskService).markDead(7L, "重试次数耗尽: boom");
    }

    @Test
    @DisplayName("taskId 以字符串 header 传入时同样能解析")
    void parsesStringTaskIdHeader() {
        MessageProperties properties = new MessageProperties();
        properties.setHeader(EncodeTaskMessage.TASK_ID_HEADER, "15");
        Message message = new Message(new byte[0], properties);

        assertThatThrownBy(() -> recoverer.recover(message, new IllegalStateException("x")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verify(taskService).markDead(15L, "重试次数耗尽: x");
    }

    private static Message message(Long taskId, String body) {
        MessageProperties properties = new MessageProperties();
        properties.setHeader(EncodeTaskMessage.TASK_ID_HEADER, taskId);
        return new Message(body.getBytes(), properties);
    }
}
