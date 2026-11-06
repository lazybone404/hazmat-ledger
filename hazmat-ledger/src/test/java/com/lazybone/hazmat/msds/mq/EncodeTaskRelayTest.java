package com.lazybone.hazmat.msds.mq;

import com.lazybone.hazmat.entity.MsdsEncodeTask;
import com.lazybone.hazmat.entity.MsdsEncodeTaskStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * relay 的补发决策（不依赖 broker）。
 *
 * <p>relay 只负责「把任务表里该有的消息补上」：
 * 还有重试次数的任务重发；次数已耗尽的直接转 DEAD，避免制造永远抢不到的死循环消息。
 */
class EncodeTaskRelayTest {

    private final EncodeTaskService taskService = mock(EncodeTaskService.class);
    private final RabbitEncodeTaskDispatcher dispatcher = mock(RabbitEncodeTaskDispatcher.class);
    private final MsdsMqProperties properties = new MsdsMqProperties();
    private final EncodeTaskRelay relay = new EncodeTaskRelay(
            taskService, dispatcher, properties, new SimpleMeterRegistry());

    @Test
    void publishesRetryableTasksAndMarksExhaustedOneDead() {
        MsdsEncodeTask pending = task(1L, MsdsEncodeTaskStatus.PENDING, 0, 3);
        MsdsEncodeTask exhausted = task(2L, MsdsEncodeTaskStatus.FAILED, 3, 3);
        MsdsEncodeTask failed = task(3L, MsdsEncodeTaskStatus.FAILED, 1, 3);
        when(taskService.findRelayCandidates(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(pending, exhausted, failed));

        relay.relay();

        verify(dispatcher).publish(1L);
        verify(dispatcher).publish(3L);
        verify(dispatcher, never()).publish(2L);
        verify(taskService).markDead(eq(2L), contains("重试次数已耗尽"));
    }

    @Test
    void emptyCandidateListDoesNothing() {
        when(taskService.findRelayCandidates(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of());

        relay.relay();

        verify(dispatcher, never()).publish(any());
        verify(taskService, never()).markDead(any(), any());
    }

    @Test
    void databaseFailureDoesNotKillTheScheduledTask() {
        when(taskService.findRelayCandidates(any(LocalDateTime.class), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(relay::relay).doesNotThrowAnyException();
    }

    private static MsdsEncodeTask task(Long id,
                                       MsdsEncodeTaskStatus status,
                                       int attempts,
                                       int maxAttempts) {
        return MsdsEncodeTask.builder()
                .id(id)
                .status(status)
                .attempts(attempts)
                .maxAttempts(maxAttempts)
                .build();
    }
}
