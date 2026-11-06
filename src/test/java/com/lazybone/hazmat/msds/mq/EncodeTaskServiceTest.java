package com.lazybone.hazmat.msds.mq;

import com.lazybone.hazmat.entity.MsdsEncodeTask;
import com.lazybone.hazmat.entity.MsdsEncodeTaskStatus;
import com.lazybone.hazmat.repository.MsdsEncodeTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 编码任务状态机（不依赖 broker）。
 *
 * <p>这里验证的是 MQ 落地后真正决定正确性的部分：重复消息只能 claim 一次、
 * 崩溃后过期的 RUNNING 能被接管、重试次数有上限、旧版本任务不会被执行。
 * RabbitMQ 只负责把消息送到这里，送到几次由它决定；去重和状态迁移必须在数据库里完成。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:encode-task-service;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "hazmat.ai.encode-on-startup=false",
        "hazmat.mq.enabled=false"
})
class EncodeTaskServiceTest {

    private static final int MAX_ATTEMPTS = 3;

    @Autowired
    private EncodeTaskService taskService;

    @Autowired
    private MsdsEncodeTaskRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void createReusesActiveTaskForSameVersion() {
        MsdsEncodeTask first = taskService.createOrReuse("v1");
        MsdsEncodeTask second = taskService.createOrReuse("v1");
        MsdsEncodeTask otherVersion = taskService.createOrReuse("v2");

        assertThat(first.getStatus()).isEqualTo(MsdsEncodeTaskStatus.PENDING);
        assertThat(first.getAttempts()).isZero();
        assertThat(first.getMaxAttempts()).isEqualTo(MAX_ATTEMPTS);
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(otherVersion.getId()).isNotEqualTo(first.getId());
    }

    @Test
    void exhaustedFailedTaskIsNotReused() {
        save("v1", MsdsEncodeTaskStatus.FAILED, MAX_ATTEMPTS,
                null, LocalDateTime.now().minusMinutes(1));

        MsdsEncodeTask created = taskService.createOrReuse("v1");

        assertThat(created.getStatus()).isEqualTo(MsdsEncodeTaskStatus.PENDING);
        assertThat(created.getAttempts()).isZero();
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void claimSucceedsOnlyOnceWhileRunning() {
        MsdsEncodeTask task = taskService.createOrReuse("v1");

        Optional<MsdsEncodeTask> first = taskService.claim(task.getId(), "worker-a");
        Optional<MsdsEncodeTask> second = taskService.claim(task.getId(), "worker-b");

        assertThat(first).isPresent();
        assertThat(first.orElseThrow().getStatus()).isEqualTo(MsdsEncodeTaskStatus.RUNNING);
        assertThat(first.orElseThrow().getAttempts()).isEqualTo(1);
        assertThat(first.orElseThrow().getWorkerId()).isEqualTo("worker-a");
        assertThat(first.orElseThrow().getClaimedAt()).isNotNull();
        assertThat(second).isEmpty();
    }

    @Test
    void expiredRunningTaskCanBeTakenOver() {
        LocalDateTime old = LocalDateTime.now().minusMinutes(20);
        MsdsEncodeTask task = save("v1", MsdsEncodeTaskStatus.RUNNING, 1, old, old);

        Optional<MsdsEncodeTask> claimed = taskService.claim(task.getId(), "worker-b");

        assertThat(claimed).isPresent();
        assertThat(claimed.orElseThrow().getAttempts()).isEqualTo(2);
        assertThat(claimed.orElseThrow().getWorkerId()).isEqualTo("worker-b");
    }

    @Test
    void failedTaskCanBeClaimedAgainUntilAttemptsRunOut() {
        MsdsEncodeTask task = taskService.createOrReuse("v1");

        assertThat(taskService.claim(task.getId(), "worker-a")).isPresent();
        taskService.markFailed(task.getId(), "临时故障");
        Optional<MsdsEncodeTask> retried = taskService.claim(task.getId(), "worker-b");

        assertThat(retried).isPresent();
        assertThat(retried.orElseThrow().getStatus()).isEqualTo(MsdsEncodeTaskStatus.RUNNING);
        assertThat(retried.orElseThrow().getAttempts()).isEqualTo(2);
    }

    @Test
    void markDoneClearsLeaseAndRecordsFinishTime() {
        MsdsEncodeTask task = taskService.createOrReuse("v1");
        taskService.claim(task.getId(), "worker-a");

        taskService.markDone(task.getId());

        MsdsEncodeTask done = repository.findById(task.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(MsdsEncodeTaskStatus.DONE);
        assertThat(done.getWorkerId()).isNull();
        assertThat(done.getClaimedAt()).isNull();
        assertThat(done.getFinishedAt()).isNotNull();
        assertThat(taskService.claim(task.getId(), "worker-b")).isEmpty();
    }

    @Test
    void markDeadStopsFurtherClaims() {
        MsdsEncodeTask task = taskService.createOrReuse("v1");
        taskService.claim(task.getId(), "worker-a");

        taskService.markDead(task.getId(), "重试耗尽");

        MsdsEncodeTask dead = repository.findById(task.getId()).orElseThrow();
        assertThat(dead.getStatus()).isEqualTo(MsdsEncodeTaskStatus.DEAD);
        assertThat(dead.getLastError()).isEqualTo("重试耗尽");
        assertThat(dead.getFinishedAt()).isNotNull();
        assertThat(taskService.claim(task.getId(), "worker-b")).isEmpty();
    }

    @Test
    void skippedTaskIsDoneWithReason() {
        MsdsEncodeTask task = taskService.createOrReuse("v1");
        taskService.claim(task.getId(), "worker-a");

        taskService.markSkipped(task.getId(), "索引版本已切换");

        MsdsEncodeTask skipped = repository.findById(task.getId()).orElseThrow();
        assertThat(skipped.getStatus()).isEqualTo(MsdsEncodeTaskStatus.DONE);
        assertThat(skipped.getLastError()).isEqualTo("索引版本已切换");
    }

    @Test
    void relayCandidatesIncludeStalePendingFailedAndExpiredRunning() {
        LocalDateTime now = LocalDateTime.now();
        MsdsEncodeTask stalePending = save("v1", MsdsEncodeTaskStatus.PENDING, 0,
                null, now.minusMinutes(1));
        MsdsEncodeTask staleFailed = save("v1", MsdsEncodeTaskStatus.FAILED, 1,
                null, now.minusMinutes(1));
        MsdsEncodeTask expiredRunning = save("v1", MsdsEncodeTaskStatus.RUNNING, 1,
                now.minusMinutes(20), now.minusMinutes(20));
        MsdsEncodeTask freshPending = save("v1", MsdsEncodeTaskStatus.PENDING, 0,
                null, now);
        MsdsEncodeTask freshRunning = save("v1", MsdsEncodeTaskStatus.RUNNING, 1,
                now, now);

        List<Long> ids = taskService.findRelayCandidates(now, 50).stream()
                .map(MsdsEncodeTask::getId)
                .toList();

        assertThat(ids).contains(stalePending.getId(), staleFailed.getId(),
                expiredRunning.getId());
        assertThat(ids).doesNotContain(freshPending.getId(), freshRunning.getId());
    }

    private MsdsEncodeTask save(String version,
                                MsdsEncodeTaskStatus status,
                                int attempts,
                                LocalDateTime claimedAt,
                                LocalDateTime updatedAt) {
        return repository.save(MsdsEncodeTask.builder()
                .indexVersion(version)
                .status(status)
                .attempts(attempts)
                .maxAttempts(MAX_ATTEMPTS)
                .claimedAt(claimedAt)
                .createdAt(updatedAt)
                .updatedAt(updatedAt)
                .build());
    }
}
