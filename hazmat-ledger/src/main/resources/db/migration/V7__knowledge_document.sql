-- 三类知识源（MSDS / 国标 / SOP）的统一文档表。
-- 为什么需要它：块是检索单位，但「这份块来自哪份文档、哪一版」不该重复存在每一块上。
-- 现有 288 块全部来自 MSDS，迁移时回填 doc_type=MSDS，行为不变。
CREATE TABLE knowledge_document (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_type     VARCHAR(16)  NOT NULL,
    source_code  VARCHAR(128) NOT NULL,
    title        VARCHAR(200) NOT NULL,
    cas_no       VARCHAR(32),
    version      VARCHAR(64),
    source_path  VARCHAR(255),
    content_hash VARCHAR(64),
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)
);

CREATE UNIQUE INDEX uk_knowledge_document_source ON knowledge_document (source_code);
CREATE INDEX idx_knowledge_document_type ON knowledge_document (doc_type, cas_no);

-- 块上冗余 doc_type / cas_no 是给检索过滤用的：过滤条件下推到块，避免每次回查文档表。
-- clause_no 存条款号（国标 5.2.1、SOP 步骤号）；MSDS 仍用 section_no。
-- index_version 为版本化切换预留，现有行为下由重建流程写入。
ALTER TABLE msds_chunk ADD COLUMN document_id BIGINT;
ALTER TABLE msds_chunk ADD COLUMN doc_type VARCHAR(16) DEFAULT 'MSDS' NOT NULL;
ALTER TABLE msds_chunk ADD COLUMN clause_no VARCHAR(64);
ALTER TABLE msds_chunk ADD COLUMN index_version VARCHAR(64);

CREATE INDEX idx_msds_chunk_doc ON msds_chunk (doc_type, cas_no);
CREATE INDEX idx_msds_chunk_version ON msds_chunk (index_version, doc_type, cas_no);
