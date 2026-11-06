-- =====================================================================
-- pgvector 初始化脚本（容器首次创建数据卷时由 postgres 镜像执行）
--
-- 与 MySQL 的分工：MySQL 是事实源（块、文档、元数据），pgvector 只存
-- 「块 id → 向量」这一份可重建的派生数据。丢了这个库，重建索引即可恢复。
--
-- 维度固定 1024（bge-m3）。换了维度必须同时清空本表并重建索引——
-- 向量的维度是列定义的一部分，不能混存。
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS msds_vector (
    chunk_id      BIGINT PRIMARY KEY,
    cas_no        VARCHAR(32),
    doc_type      VARCHAR(16),
    index_version VARCHAR(64),
    model_id      VARCHAR(64),
    embedding     vector(1024) NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- HNSW：近似最近邻。m 与 ef_construction 取 pgvector 推荐默认值；
-- 召回率与构建耗时的权衡见 docs/实验记录.md 的 E5 小节
CREATE INDEX IF NOT EXISTS idx_msds_vector_hnsw
    ON msds_vector USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- 过滤列（版本 / 类型 / CAS）：检索先按这些条件缩小范围，再走 HNSW
CREATE INDEX IF NOT EXISTS idx_msds_vector_filter
    ON msds_vector (index_version, doc_type, cas_no);
