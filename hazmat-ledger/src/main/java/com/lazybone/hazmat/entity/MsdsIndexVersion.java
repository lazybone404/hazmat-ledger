package com.lazybone.hazmat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 索引版本台账。块上的 {@code index_version} 是事实，这张表回答「一共有过哪些版本、
 * 各自处于什么状态、多大」。用它做运维排查与回滚审计，而不是每次对块表做 group by。
 *
 * <p>状态：{@link #ACTIVE} 当前生效、{@link #PREVIOUS} 回滚目标、
 * {@link #RETIRED} 已不在保留集内（块行通常也已被清理）。没有 BUILDING：
 * 重建在同一个事务里完成「写块 + 切指针」，中间态对外不可见。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "msds_index_version")
public class MsdsIndexVersion {

    public static final String ACTIVE = "ACTIVE";
    public static final String PREVIOUS = "PREVIOUS";
    public static final String RETIRED = "RETIRED";

    @Id
    @Column(name = "version", length = 64)
    private String version;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "switched_at")
    private LocalDateTime switchedAt;

    @Column(name = "retired_at")
    private LocalDateTime retiredAt;
}
