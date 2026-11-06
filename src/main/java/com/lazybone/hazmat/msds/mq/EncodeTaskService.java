package com.lazybone.hazmat.msds.mq;

import com.lazybone.hazmat.entity.MsdsEncodeTask;
import com.lazybone.hazmat.entity.MsdsEncodeTaskStatus;
import com.lazybone.hazmat.repository.MsdsEncodeTaskRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 编码任务的状态机：创建/复用、claim、完成、失败、死信与 relay 候选查询。
 *
 * <p>这里刻意不依赖 RabbitMQ：任务表才是事实源，MQ 只是唤醒信号。
 * 因此单元测试可以不起 broker，直接验证「重复 claim 只成功一次」「租约过期可接管」
 * 这类真正决定正确性的规则。
 */
@Service
public class EncodeTaskService {

    private static final Logger log = LoggerFactory.getLogger(EncodeTaskService.class);

    private static final List<MsdsEncodeTaskStatus> REUSABLE = List.of(
            MsdsEncodeTaskStatus.PENDING,
            MsdsEncodeTaskStatus.RUNNING,
            MsdsEncodeTaskStatus.FAILED);

    /** 可以直接被 claim 的状态；RUNNING 走租约过期分支，不能出现在这里 */
    private static final List<MsdsEncodeTaskStatus> RETRYABLE = List.of(
            MsdsEncodeTaskStatus.PENDING,
            MsdsEncodeTaskStatus.FAILED);

    private static final List<MsdsEncodeTaskStatus> ACTIVE = List.of(
            MsdsEncodeTaskStatus.PENDING,
            MsdsEncodeTaskStatus.RUNNING,
            MsdsEncodeTaskStatus.FAILED);

    private static final int MAX_ERROR_LENGTH = 1000;

    private final MsdsEncodeTaskRepository repository;
    private final MsdsMqProperties properties;
    private final MeterRegistry meterRegistry;

    public EncodeTaskService(MsdsEncodeTaskRepository repository,
                             MsdsMqProperties properties,
                             MeterRegistry meterRegistry) {
        this.repository = repository;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        Gauge.builder("msds.encode.task.pending", this,
                        service -> (double) service.pendingCount())
                .description("等待处理的 MSDS 编码任务数")
                .register(meterRegistry);
    }

    /**
     * 同一索引版本只保留一个未完成任务；显式 reindex 天然是幂等的。
     *
     * <p>FAILED 且已经用满重试次数的任务不再复用——它已经走到终态，
     * 复用只会让新请求拿到一个永远不会再执行的任务 id。
     */
    @Transactional
    public MsdsEncodeTask createOrReuse(String indexVersion) {
        List<MsdsEncodeTask> candidates = indexVersion == null
                ? repository.findByIndexVersionIsNullAndStatusInOrderByCreatedAtDesc(REUSABLE)
                : repository.findByIndexVersionAndStatusInOrderByCreatedAtDesc(indexVersion, REUSABLE);
        Optional<MsdsEncodeTask> reusable = candidates.stream()
                .filter(task -> task.getStatus() != MsdsEncodeTaskStatus.FAILED
                        || task.getAttempts() < task.getMaxAttempts())
                .findFirst();
        if (reusable.isPresent()) {
            return reusable.get();
        }

        LocalDateTime now = LocalDateTime.now();
        MsdsEncodeTask created = MsdsEncodeTask.builder()
                .indexVersion(indexVersion)
                .status(MsdsEncodeTaskStatus.PENDING)
                .attempts(0)
                .maxAttempts(Math.max(1, properties.getEncode().getMaxAttempts()))
                .createdAt(now)
                .updatedAt(now)
                .build();
        repository.save(created);
        count("created");
        log.info("创建 MSDS 编码任务: id={}, indexVersion={}", created.getId(), indexVersion);
        return created;
    }

    /**
     * 原子抢占。返回空表示这次消息是重复投递，或者任务已被别的实例拿走。
     */
    @Transactional
    public Optional<MsdsEncodeTask> claim(Long id, String workerId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseCutoff = now.minus(Duration.ofMillis(
                Math.max(1, properties.getEncode().getClaimLeaseMs())));
        int updated = repository.claim(id, MsdsEncodeTaskStatus.RUNNING, RETRYABLE,
                workerId, now, leaseCutoff);
        if (updated == 0) {
            count("duplicate");
            return Optional.empty();
        }
        count("claimed");
        return repository.findById(id);
    }

    @Transactional
    public void markDone(Long id) {
        int updated = repository.markDone(id, MsdsEncodeTaskStatus.DONE,
                MsdsEncodeTaskStatus.RUNNING, LocalDateTime.now());
        if (updated > 0) {
            count("done");
            log.info("MSDS 编码任务完成: id={}", id);
        }
    }

    @Transactional
    public void markSkipped(Long id, String reason) {
        int updated = repository.markSkipped(id, MsdsEncodeTaskStatus.DONE, ACTIVE,
                truncate(reason), LocalDateTime.now());
        if (updated > 0) {
            count("skipped");
            log.info("MSDS 编码任务跳过: id={}, reason={}", id, reason);
        }
    }

    @Transactional
    public void markFailed(Long id, String error) {
        String reason = truncate(error);
        int updated = repository.markFailed(id, MsdsEncodeTaskStatus.FAILED,
                MsdsEncodeTaskStatus.RUNNING, reason, LocalDateTime.now());
        if (updated > 0) {
            count("failed");
            log.warn("MSDS 编码任务失败，等待重试: id={}, reason={}", id, reason);
        }
    }

    @Transactional
    public void markDead(Long id, String error) {
        String reason = truncate(error);
        int updated = repository.markDead(id, MsdsEncodeTaskStatus.DEAD, ACTIVE,
                reason, LocalDateTime.now());
        if (updated > 0) {
            count("dead");
            log.error("MSDS 编码任务进入死信状态: id={}, reason={}", id, reason);
        }
    }

    @Transactional(readOnly = true)
    public Optional<MsdsEncodeTask> find(Long id) {
        return repository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<EncodeTaskView> recent(int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        return repository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, size)).stream()
                .map(EncodeTaskView::from)
                .toList();
    }

    /**
     * relay 的候选集：
     * <ul>
     *   <li>PENDING / FAILED 静置超过 {@code relay-min-age-ms} 未推进；</li>
     *   <li>RUNNING 的 claim 时间超过租约，说明持有它的实例可能已经崩溃。</li>
     * </ul>
     * 两类合并后按批大小截断；重复消息由 claim 去重。
     */
    @Transactional(readOnly = true)
    public List<MsdsEncodeTask> findRelayCandidates(LocalDateTime now, int batchSize) {
        int size = Math.max(1, Math.min(batchSize, 500));
        LocalDateTime minAgeCutoff = now.minus(Duration.ofMillis(
                Math.max(1, properties.getEncode().getRelayMinAgeMs())));
        LocalDateTime leaseCutoff = now.minus(Duration.ofMillis(
                Math.max(1, properties.getEncode().getClaimLeaseMs())));

        Map<Long, MsdsEncodeTask> merged = new LinkedHashMap<>();
        repository.findStale(RETRYABLE, minAgeCutoff, PageRequest.of(0, size))
                .forEach(task -> merged.put(task.getId(), task));
        repository.findExpiredRunning(MsdsEncodeTaskStatus.RUNNING, leaseCutoff,
                        PageRequest.of(0, size))
                .forEach(task -> merged.putIfAbsent(task.getId(), task));
        return merged.values().stream().limit(size).toList();
    }

    public long pendingCount() {
        return repository.countByStatus(MsdsEncodeTaskStatus.PENDING);
    }

    private void count(String event) {
        meterRegistry.counter("msds.encode.task", "event", event).increment();
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}
