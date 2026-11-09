package com.lazybone.hazmat.service;

import com.lazybone.hazmat.entity.DocumentNoSegment;
import com.lazybone.hazmat.repository.DocumentNoSegmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * 从数据库分配号段。
 *
 * <p>用显式事务模板而不是 {@code @Transactional}：号段行不存在时要插入，
 * 两个实例同时插入会撞唯一约束。冲突发生在内层事务里，必须等它完整回滚后
 * 再开一个新事务重试；在同一个 {@code @Transactional} 方法里 catch 住异常，
 * 事务已经被标记 rollback-only，重试不会生效。
 *
 * <p>{@code REQUIRES_NEW} 会让分配短暂占用第二个数据库连接。建单不是高频路径，
 * 连接池 10 足够；如果以后建单 QPS 大幅上升，需要把分配改成独立服务或预取更多号段。
 */
@Slf4j
@Service
public class DocumentNoSegmentAllocator {

    private final DocumentNoSegmentRepository repository;
    private final TransactionTemplate transactionTemplate;

    public DocumentNoSegmentAllocator(DocumentNoSegmentRepository repository,
                                      PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 分配 {@code [start, start + blockSize)}，返回起始值。
     * 行不存在时插入；唯一冲突后回滚并重试一次。
     */
    public long allocate(String prefix, int blockSize) {
        if (blockSize < 1) {
            throw new IllegalArgumentException("blockSize 必须大于 0: " + blockSize);
        }
        try {
            return allocateOnce(prefix, blockSize);
        } catch (DataIntegrityViolationException e) {
            log.info("号段 {} 插入冲突，回滚后重试一次", prefix);
            return allocateOnce(prefix, blockSize);
        }
    }

    private long allocateOnce(String prefix, int blockSize) {
        Long start = transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            DocumentNoSegment segment = repository.findForUpdate(prefix)
                    .orElseGet(() -> DocumentNoSegment.builder()
                            .prefix(prefix)
                            .nextValue(1L)
                            .updatedAt(now)
                            .build());
            long startValue = segment.getNextValue();
            segment.setNextValue(startValue + blockSize);
            segment.setUpdatedAt(now);
            repository.saveAndFlush(segment);
            return startValue;
        });
        if (start == null) {
            throw new IllegalStateException("号段分配未返回结果: " + prefix);
        }
        return start;
    }
}
