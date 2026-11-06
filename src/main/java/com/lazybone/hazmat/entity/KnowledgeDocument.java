package com.lazybone.hazmat.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 一份知识源文档（MSDS / 国标 / SOP）。
 *
 * <p>块上已经有 {@code doc_type} 与 {@code cas_no} 供检索过滤，这里再存一份文档级元数据，
 * 是为了回答「这块来自哪份文件、哪一版」，并且让重建可以按文档增量而不是全库扫。
 *
 * <p>{@code source_code} 是稳定业务键：MSDS 用文件名（CAS），国标用标准号，SOP 用文件名。
 * 重建时按它 upsert，不按数据库自增 ID，否则文件改名会多出一份「幽灵文档」。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "knowledge_document", indexes = {
        @Index(name = "uk_knowledge_document_source", columnList = "source_code", unique = true),
        @Index(name = "idx_knowledge_document_type", columnList = "doc_type,cas_no")
})
public class KnowledgeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 16)
    private KnowledgeDocType docType;

    /** MSDS 用 CAS（文件名），国标用标准号（如 GB 15603-2022），SOP 用文件名 */
    @Column(name = "source_code", nullable = false, length = 128, unique = true)
    private String sourceCode;

    @Column(nullable = false, length = 200)
    private String title;

    /** 只对化学品类文档有意义；国标与 SOP 可为空 */
    @Column(name = "cas_no", length = 32)
    private String casNo;

    /** 文档版本，如 MSDS 修订日期、标准年号；用于排查而不是检索过滤 */
    @Column(length = 64)
    private String version;

    /** classpath 相对路径，重建时用来发现文件是否还在 */
    @Column(name = "source_path", length = 255)
    private String sourcePath;

    /** 原文 SHA-256。未变化时重建可以跳过切块，只复用块 */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
