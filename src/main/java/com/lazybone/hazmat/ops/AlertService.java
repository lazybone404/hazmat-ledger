package com.lazybone.hazmat.ops;

import com.lazybone.hazmat.common.ConflictException;
import com.lazybone.hazmat.common.ResourceNotFoundException;
import com.lazybone.hazmat.entity.AlertSeverity;
import com.lazybone.hazmat.entity.OpsAlert;
import com.lazybone.hazmat.repository.OpsAlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * 运维告警的写入与解决。
 *
 * <p>告警写入分两步：先按 fingerprint 尝试 UPDATE，影响 0 行再 INSERT。
 * 两步各自跑在独立事务（{@code REQUIRES_NEW}）里，原因有两个：
 * <ul>
 *   <li>定时任务本身可能处在一个大事务里，INSERT 撞唯一索引会把调用方的事务标记成
 *       rollback-only，之后整个任务都提交不了；</li>
 *   <li>INSERT 撞唯一索引说明另一个实例刚插入了同一 fingerprint，它的事务已经提交，
 *       此时在新事务里重试 UPDATE 一定能命中，不需要再处理冲突。</li>
 * </ul>
 *
 * <p>不做 leader election：多实例会各跑一遍任务，靠 fingerprint 去重把结果收敛成一行，
 * {@code occurrences} 会比单实例跑时多，这是可接受的。
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final OpsAlertRepository repository;
    private final TransactionTemplate transactionTemplate;

    public AlertService(OpsAlertRepository repository,
                        PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 记录一次故障。同一 fingerprint 重复调用只累加次数，不产生新行；
     * 已解决的告警会被重新打开。
     */
    public void raise(String type, AlertSeverity severity, String fingerprint, String message) {
        LocalDateTime now = LocalDateTime.now();
        if (touch(fingerprint, message, severity, now)) {
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> repository.saveAndFlush(
                    OpsAlert.builder()
                            .alertType(type)
                            .severity(severity)
                            .fingerprint(fingerprint)
                            .message(message)
                            .occurrences(1)
                            .firstSeenAt(now)
                            .lastSeenAt(now)
                            .build()));
        } catch (DataIntegrityViolationException e) {
            // 另一个实例抢先插入了同一 fingerprint，它的行已经提交，这里补一次累加
            log.debug("告警 {} 并发写入，改为累加已存在的行", fingerprint);
            touch(fingerprint, message, severity, now);
        }
    }

    @Transactional
    public OpsAlert resolve(Long id, String operator) {
        LocalDateTime now = LocalDateTime.now();
        if (repository.resolve(id, operator, now) == 0) {
            repository.findById(id).orElseThrow(
                    () -> new ResourceNotFoundException("告警不存在: id=" + id));
            throw new ConflictException("告警已经解决: id=" + id);
        }
        return repository.findById(id).orElseThrow(
                () -> new ResourceNotFoundException("告警不存在: id=" + id));
    }

    @Transactional(readOnly = true)
    public Page<OpsAlert> page(Boolean resolved, String type, Pageable pageable) {
        Specification<OpsAlert> spec = (root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (resolved != null) {
                predicates.add(resolved
                        ? cb.isNotNull(root.get("resolvedAt"))
                        : cb.isNull(root.get("resolvedAt")));
            }
            if (type != null && !type.isBlank()) {
                predicates.add(cb.equal(root.get("alertType"), type));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        return repository.findAll(spec, pageable);
    }

    private boolean touch(String fingerprint, String message, AlertSeverity severity, LocalDateTime now) {
        Boolean hit = transactionTemplate.execute(status ->
                repository.touch(fingerprint, message, severity, now) > 0);
        return Boolean.TRUE.equals(hit);
    }
}
