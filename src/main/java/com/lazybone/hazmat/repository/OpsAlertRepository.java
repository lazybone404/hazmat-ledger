package com.lazybone.hazmat.repository;

import com.lazybone.hazmat.entity.AlertSeverity;
import com.lazybone.hazmat.entity.OpsAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface OpsAlertRepository
        extends JpaRepository<OpsAlert, Long>, JpaSpecificationExecutor<OpsAlert> {

    /**
     * 同一次故障再次出现：累加次数、刷新内容与时间，并把已解决的告警重新打开。
     *
     * <p>条件里<b>不</b>限制 {@code resolvedAt is null}。只更新未解决行的话，
     * 一条告警被解决后再发生，这条 UPDATE 影响 0 行，接着 INSERT 又撞唯一索引，
     * 结果是「修好过一次的问题再也报不出来」。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OpsAlert a
               set a.occurrences = a.occurrences + 1,
                   a.lastSeenAt = :now,
                   a.message = :message,
                   a.severity = :severity,
                   a.resolvedAt = null,
                   a.resolvedBy = null
             where a.fingerprint = :fingerprint
            """)
    int touch(@Param("fingerprint") String fingerprint,
              @Param("message") String message,
              @Param("severity") AlertSeverity severity,
              @Param("now") LocalDateTime now);

    /** 只解决未解决的告警。返回 0 表示不存在或已经被别人解决 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OpsAlert a
               set a.resolvedAt = :now, a.resolvedBy = :operator
             where a.id = :id and a.resolvedAt is null
            """)
    int resolve(@Param("id") Long id,
                @Param("operator") String operator,
                @Param("now") LocalDateTime now);
}
