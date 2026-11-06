package com.lazybone.hazmat.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 一次 MSDS 编码任务，对应 msds_encode_task 表。
 *
 * <p>为什么任务要落库而不是只发一条消息：消息丢了就没了，数据库行还在。
 * 任务行同时承担 outbox 的角色——先提交任务，再发消息；发送失败时保持 PENDING，
 * 由定时 relay 补发。重复投递由 {@code claim} 的条件更新去重。
 *
 * <p>{@code indexVersion} 允许为空：升级前落库的块没有版本号，仍要能编码。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "msds_encode_task", indexes = {
        @Index(name = "idx_msds_encode_task_status", columnList = "status,updated_at"),
        @Index(name = "idx_msds_encode_task_version", columnList = "index_version,status")
})
public class MsdsEncodeTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 任务针对的索引版本；为空表示版本化之前的历史块 */
    @Column(name = "index_version", length = 64)
    private String indexVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MsdsEncodeTaskStatus status;

    /** 已被 claim 的次数。每次 claim 自增，达到 maxAttempts 后不再抢占 */
    @Column(nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    /** 当前持有任务的实例标识，仅用于排查；不参与正确性判断 */
    @Column(name = "worker_id", length = 64)
    private String workerId;

    /** 最近一次失败原因，截断到 1000 字符 */
    @Column(name = "last_error", length = 1000)
    private String lastError;

    /** 最近一次 claim 时间。RUNNING 超过租约没更新，允许其他实例接管 */
    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
}
