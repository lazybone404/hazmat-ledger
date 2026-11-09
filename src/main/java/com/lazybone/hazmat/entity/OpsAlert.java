package com.lazybone.hazmat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 运维告警。定时任务发现问题写这里，而不是只打日志。
 *
 * <p>{@code fingerprint} 是「同一次故障」的判定依据：重复扫描只累加
 * {@code occurrences}、刷新最后出现时间，不产生新行。已经解决的告警再次出现时，
 * {@code raise} 会把 {@code resolvedAt/resolvedBy} 清空并重新打开——否则修好一次之后，
 * 同一个问题再发生就永远写不进来了。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ops_alert", indexes = {
        @Index(name = "idx_ops_alert_resolved_last_seen", columnList = "resolvedAt,lastSeenAt")
})
public class OpsAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 告警类型：EXPIRY / RECONCILE / STOCKTAKE_REVIEW_STALE 等，供筛选 */
    @Column(nullable = false, length = 40)
    private String alertType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AlertSeverity severity;

    /** 同一故障的稳定标识，唯一。例如 {@code RECONCILE:42} */
    @Column(nullable = false, length = 200)
    private String fingerprint;

    @Column(nullable = false, length = 1000)
    private String message;

    /** 首次出现后累计次数。只增不减，用来区分「偶发一次」与「一直在报」 */
    @Column(nullable = false)
    private Integer occurrences;

    @Column(nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(nullable = false)
    private LocalDateTime lastSeenAt;

    private LocalDateTime resolvedAt;

    @Column(length = 100)
    private String resolvedBy;
}
