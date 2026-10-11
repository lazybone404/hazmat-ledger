package com.lazybone.hazmat.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 向量索引的「身份证」——这批向量是用什么模型、什么维度、哪一版切块规则算出来的。
 *
 * 固定一行（id=1）。四元组任一项与当前配置不符，旧向量就不能再用：不同模型算出的
 * 向量不在同一个空间，余弦数值上算得出来，排序却没有意义。此时向量路整体停用，
 * 等用户显式重建，而不是悄悄混用。
 *
 * {@code pausedAt} / {@code pausedReason} 持久化暂停状态。只放内存的话，重启后
 * 会重新尝试失败的那批块——key 没修好，重启一次白打一轮模型调用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "msds_index_meta")
public class MsdsIndexMeta {

    /** 固定为 1 */
    @Id
    private Integer id;

    @Column(name = "model_id", length = 64)
    private String modelId;

    @Column(name = "dimension")
    private Integer dimension;

    @Column(name = "chunk_version")
    private Integer chunkVersion;

    @Column(name = "schema_version")
    private Integer schemaVersion;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "paused_at")
    private LocalDateTime pausedAt;

    @Column(name = "paused_reason", length = 255)
    private String pausedReason;
}
