package com.lazybone.hazmat.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * MSDS 检索块 —— 一份安全技术说明书的每一节落一行，对应 msds_chunk 表。
 *
 * 为什么不把整份 MSDS 存成一行再在内存里切：块要能单独标记「向量是否已编码」。
 * 整篇一行的话，改一个字就得重算全文向量，也没法回答「哪一块还没编码」。
 *
 * {@code chemicalId} 允许为空：MSDS 文件可能比试剂档案先导入，索引不该因此失败，
 * 返回结果时按 {@code casNo} 再补名称即可。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "msds_chunk", indexes = {
        @Index(name = "idx_msds_chunk_cas", columnList = "cas_no"),
        @Index(name = "idx_msds_chunk_hash", columnList = "cas_no,text_hash")
})
public class MsdsChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chemical_id")
    private Long chemicalId;

    /** 来源文档。历史数据允许为空（迁移前没有 document 表） */
    @Column(name = "document_id")
    private Long documentId;

    /**
     * 知识源类型。检索过滤直接落在块上，不每次回查文档表。
     * 历史行由 V7 迁移回填为 MSDS；新写入由索引服务显式设置。
     */
    @Column(name = "doc_type", nullable = false, length = 16)
    private String docType;

    /**
     * 检索与展示用的业务键：MSDS 是与文件名一致的 CAS（如 67-56-1）；
     * 国标/SOP 没有 CAS，用文档的 {@code source_code}（如 GB 15603-2022）填这里。
     * 列保持 NOT NULL，避免为两类新文档改历史表的可空性（H2 与 MySQL 的 ALTER 语法不一致）。
     */
    @Column(name = "cas_no", nullable = false, length = 32)
    private String casNo;

    /** 条款号（国标 5.2.1 / SOP 步骤号）；MSDS 为空，仍用 section_no */
    @Column(name = "clause_no", length = 64)
    private String clauseNo;

    /** GB/T 16483 的节号，1–16 */
    @Column(name = "section_no", nullable = false)
    private int sectionNo;

    @Column(name = "section_title", nullable = false, length = 64)
    private String sectionTitle;

    /** 同一节被切成多块时的序号，从 0 开始 */
    @Column(name = "part_no", nullable = false)
    private int partNo;

    /** 含「§章节号 标题」前缀的正文 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** SHA-256，增量重建时按它匹配旧块，而不是按下标 */
    @Column(name = "text_hash", nullable = false, length = 64)
    private String textHash;

    /**
     * 索引版本。一次重建产出一个版本，检索只读全局 active 版本。
     * 版本化切换落地前允许为空：历史数据与 memory 模式不依赖它。
     */
    @Column(name = "index_version", length = 64)
    private String indexVersion;

    /** 格式「模型名|0.1234,...」，未编码时为空 */
    @Column(columnDefinition = "TEXT")
    private String vector;

    @Column(name = "indexed_at")
    private LocalDateTime indexedAt;

    /**
     * 累计编码失败次数。达到上限后不再自动重试，只能由 reindex 显式清零。
     * 持久化在块上，重启不会「重置失败」再来一轮模型调用。
     */
    @Column(name = "encode_failures", nullable = false)
    private int encodeFailures;

    /** 最近一次编码失败原因，截断到 255 字符，供状态接口排查 */
    @Column(name = "last_error", length = 255)
    private String lastError;
}
