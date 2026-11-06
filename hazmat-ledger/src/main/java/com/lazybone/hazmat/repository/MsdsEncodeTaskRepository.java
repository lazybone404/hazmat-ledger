package com.lazybone.hazmat.repository;

import com.lazybone.hazmat.entity.MsdsEncodeTask;
import com.lazybone.hazmat.entity.MsdsEncodeTaskStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 编码任务的状态机查询。
 *
 * <p>所有状态迁移都用条件 UPDATE，而不是「先查后改」：
 * 多实例、重复消息、relay 补发会同时到达，先查后改会让两个消费者都以为自己拿到了任务。
 * 条件更新把判断和写入放在一条语句里，返回 0 就代表这次没抢到。
 */
public interface MsdsEncodeTaskRepository extends JpaRepository<MsdsEncodeTask, Long> {

    List<MsdsEncodeTask> findByIndexVersionAndStatusInOrderByCreatedAtDesc(
            String indexVersion, Collection<MsdsEncodeTaskStatus> statuses);

    List<MsdsEncodeTask> findByIndexVersionIsNullAndStatusInOrderByCreatedAtDesc(
            Collection<MsdsEncodeTaskStatus> statuses);

    List<MsdsEncodeTask> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByStatus(MsdsEncodeTaskStatus status);

    /**
     * 抢占任务。PENDING / FAILED 可直接抢；RUNNING 只有在 claimed_at 超过租约后才允许接管，
     * 用来恢复「实例崩溃、消息重投但任务卡在 RUNNING」的情况。
     *
     * <p>条件里的 {@code attempts < maxAttempts} 是最后一道闸：重试耗尽的任务
     * 即使消息还在，也不会再调用模型。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MsdsEncodeTask t
               set t.status = :runningStatus,
                   t.workerId = :worker,
                   t.claimedAt = :now,
                   t.attempts = t.attempts + 1,
                   t.updatedAt = :now
             where t.id = :id
               and t.attempts < t.maxAttempts
               and (t.status in :retryable
                    or (t.status = :runningStatus
                        and (t.claimedAt is null or t.claimedAt < :leaseCutoff)))
            """)
    int claim(@Param("id") Long id,
              @Param("runningStatus") MsdsEncodeTaskStatus runningStatus,
              @Param("retryable") Collection<MsdsEncodeTaskStatus> retryable,
              @Param("worker") String worker,
              @Param("now") LocalDateTime now,
              @Param("leaseCutoff") LocalDateTime leaseCutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MsdsEncodeTask t
               set t.status = :done,
                   t.finishedAt = :now,
                   t.updatedAt = :now,
                   t.workerId = null,
                   t.claimedAt = null,
                   t.lastError = null
             where t.id = :id and t.status = :running
            """)
    int markDone(@Param("id") Long id,
                 @Param("done") MsdsEncodeTaskStatus done,
                 @Param("running") MsdsEncodeTaskStatus running,
                 @Param("now") LocalDateTime now);

    /** 旧版本任务不再执行，但仍然要留下「为什么没做」的痕迹 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MsdsEncodeTask t
               set t.status = :done,
                   t.finishedAt = :now,
                   t.updatedAt = :now,
                   t.workerId = null,
                   t.claimedAt = null,
                   t.lastError = :reason
             where t.id = :id and t.status in :active
            """)
    int markSkipped(@Param("id") Long id,
                    @Param("done") MsdsEncodeTaskStatus done,
                    @Param("active") Collection<MsdsEncodeTaskStatus> active,
                    @Param("reason") String reason,
                    @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MsdsEncodeTask t
               set t.status = :failed,
                   t.lastError = :error,
                   t.updatedAt = :now,
                   t.finishedAt = null,
                   t.workerId = null,
                   t.claimedAt = null
             where t.id = :id and t.status = :running
            """)
    int markFailed(@Param("id") Long id,
                   @Param("failed") MsdsEncodeTaskStatus failed,
                   @Param("running") MsdsEncodeTaskStatus running,
                   @Param("error") String error,
                   @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MsdsEncodeTask t
               set t.status = :dead,
                   t.lastError = :error,
                   t.updatedAt = :now,
                   t.finishedAt = :now,
                   t.workerId = null,
                   t.claimedAt = null
             where t.id = :id and t.status in :active
            """)
    int markDead(@Param("id") Long id,
                 @Param("dead") MsdsEncodeTaskStatus dead,
                 @Param("active") Collection<MsdsEncodeTaskStatus> active,
                 @Param("error") String error,
                 @Param("now") LocalDateTime now);

    /** relay 用：PENDING / FAILED 超过最小年龄仍未推进的任务 */
    @Query("""
            select t from MsdsEncodeTask t
             where t.status in :statuses and t.updatedAt < :cutoff
             order by t.updatedAt asc
            """)
    List<MsdsEncodeTask> findStale(@Param("statuses") Collection<MsdsEncodeTaskStatus> statuses,
                                   @Param("cutoff") LocalDateTime cutoff,
                                   Pageable pageable);

    /** relay 用：RUNNING 超过租约没有更新，视为持有者已经失联 */
    @Query("""
            select t from MsdsEncodeTask t
             where t.status = :running
               and (t.claimedAt is null or t.claimedAt < :cutoff)
             order by t.claimedAt asc
            """)
    List<MsdsEncodeTask> findExpiredRunning(@Param("running") MsdsEncodeTaskStatus running,
                                            @Param("cutoff") LocalDateTime cutoff,
                                            Pageable pageable);
}
