package com.lazybone.hazmat.msds.mq;

import com.lazybone.hazmat.entity.MsdsEncodeTask;
import com.lazybone.hazmat.entity.MsdsEncodeTaskStatus;
import com.lazybone.hazmat.repository.MsdsEncodeTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 编码任务保留清理：只删终态且已完成足够久的任务。
 *
 * <p>边界比「删旧行」复杂一点：PENDING / RUNNING 还在流程里，FAILED 是等待重试的证据，
 * 三者都不能删。RUNNING 即使时间很早也不能删——它的 {@code finishedAt} 为空，
 * 真正决定它是否存活的是 claim 租约，不是这里的保留期。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:encode-task-retention;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "hazmat.ai.encode-on-startup=false",
        "hazmat.mq.enabled=false"
})
class EncodeTaskRetentionTest {

    @Autowired private EncodeTaskService taskService;
    @Autowired private MsdsEncodeTaskRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("只删终态且完成时间早于保留期的任务")
    void purgeOnlyRemovesOldFinishedTasks() {
        LocalDateTime old = LocalDateTime.now().minusDays(30);
        LocalDateTime recent = LocalDateTime.now().minusHours(1);

        Long doneOld = save(MsdsEncodeTaskStatus.DONE, old);
        Long deadOld = save(MsdsEncodeTaskStatus.DEAD, old);
        Long doneRecent = save(MsdsEncodeTaskStatus.DONE, recent);
        Long failedOld = save(MsdsEncodeTaskStatus.FAILED, old);
        Long pendingOld = save(MsdsEncodeTaskStatus.PENDING, null);
        Long runningOld = save(MsdsEncodeTaskStatus.RUNNING, null);

        int deleted = taskService.purgeFinishedBefore(LocalDateTime.now().minusDays(7));

        assertThat(deleted).isEqualTo(2);
        assertThat(repository.findAll()).extracting(MsdsEncodeTask::getId)
                .containsExactlyInAnyOrder(doneRecent, failedOld, pendingOld, runningOld);
        assertThat(repository.findById(doneOld)).isEmpty();
        assertThat(repository.findById(deadOld)).isEmpty();
    }

    private Long save(MsdsEncodeTaskStatus status, LocalDateTime finishedAt) {
        LocalDateTime now = LocalDateTime.now();
        return repository.save(MsdsEncodeTask.builder()
                .indexVersion("v1")
                .status(status)
                .attempts(1)
                .maxAttempts(3)
                .createdAt(now)
                .updatedAt(now)
                .finishedAt(finishedAt)
                .build()).getId();
    }
}
