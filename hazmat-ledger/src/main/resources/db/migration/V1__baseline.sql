-- =====================================================================
-- V1：初始表结构
--
-- 这套 SQL 同时跑在 H2 与 MySQL 8 上，所以只使用两边都支持的语法：
--   * 自增主键用 AUTO_INCREMENT（H2 与 MySQL 都接受）；
--   * 时间列用 DATETIME(6) 而不是 TIMESTAMP —— MySQL 的 TIMESTAMP 在部分
--     配置下会自动初始化与更新，会把 created_at 悄悄改掉；
--   * 不写 ENGINE / CHARSET，这两项由数据库实例的默认值决定。
--
-- 迁移脚本一旦发布就不再修改。后续变更一律新增 V2、V3……
-- =====================================================================

CREATE TABLE chemical (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    cas_no       VARCHAR(20)  NOT NULL,
    name         VARCHAR(200) NOT NULL,
    category     VARCHAR(50)  NOT NULL,
    hazard_level VARCHAR(20)  NOT NULL,
    unit         VARCHAR(20)  NOT NULL,
    spec         VARCHAR(100),
    msds_url     VARCHAR(300),
    created_at   DATETIME(6)  NOT NULL,
    CONSTRAINT uk_chemical_cas_no UNIQUE (cas_no)
);

CREATE TABLE chemical_batch (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    chemical_id        BIGINT        NOT NULL,
    batch_no           VARCHAR(50)   NOT NULL,
    initial_quantity   DECIMAL(12, 3) NOT NULL,
    available_quantity DECIMAL(12, 3) NOT NULL,
    location           VARCHAR(100)  NOT NULL,
    expire_date        DATE          NOT NULL,
    created_at         DATETIME(6)   NOT NULL
);

CREATE INDEX idx_batch_chemical ON chemical_batch (chemical_id);

CREATE TABLE requisition (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    req_no          VARCHAR(40)    NOT NULL,
    applicant       VARCHAR(100)   NOT NULL,
    chemical_id     BIGINT         NOT NULL,
    quantity        DECIMAL(12, 3) NOT NULL,
    purpose         VARCHAR(500)   NOT NULL,
    status          VARCHAR(20)    NOT NULL,
    reviewer        VARCHAR(100),
    review_comment  VARCHAR(500),
    first_reviewer  VARCHAR(100),
    issued_batch_id BIGINT,
    issued_quantity DECIMAL(12, 3),
    version         BIGINT         NOT NULL,
    created_at      DATETIME(6)    NOT NULL,
    reviewed_at     DATETIME(6),
    issued_at       DATETIME(6),
    closed_at       DATETIME(6),
    CONSTRAINT uk_requisition_req_no UNIQUE (req_no)
);

CREATE INDEX idx_req_applicant ON requisition (applicant);
CREATE INDEX idx_req_status ON requisition (status);

CREATE TABLE stock_ledger (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id      BIGINT         NOT NULL,
    chemical_id   BIGINT         NOT NULL,
    change_type   VARCHAR(10)    NOT NULL,
    quantity      DECIMAL(12, 3) NOT NULL,
    balance_after DECIMAL(12, 3) NOT NULL,
    ref_type      VARCHAR(20)    NOT NULL,
    ref_id        BIGINT,
    operator      VARCHAR(100)   NOT NULL,
    created_at    DATETIME(6)    NOT NULL
);

CREATE INDEX idx_ledger_batch ON stock_ledger (batch_id);
CREATE INDEX idx_ledger_ref ON stock_ledger (ref_type, ref_id);

CREATE TABLE requisition_log (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    requisition_id BIGINT       NOT NULL,
    from_status    VARCHAR(20),
    to_status      VARCHAR(20)  NOT NULL,
    operator       VARCHAR(100) NOT NULL,
    comment        VARCHAR(300),
    created_at     DATETIME(6)  NOT NULL
);

CREATE INDEX idx_log_req ON requisition_log (requisition_id);

CREATE TABLE msds_chunk (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    chemical_id     BIGINT,
    cas_no          VARCHAR(32) NOT NULL,
    section_no      INT         NOT NULL,
    section_title   VARCHAR(64) NOT NULL,
    part_no         INT         NOT NULL,
    content         TEXT        NOT NULL,
    text_hash       VARCHAR(64) NOT NULL,
    vector          TEXT,
    indexed_at      DATETIME(6),
    encode_failures INT         NOT NULL,
    last_error      VARCHAR(255)
);

CREATE INDEX idx_msds_chunk_cas ON msds_chunk (cas_no);
CREATE INDEX idx_msds_chunk_hash ON msds_chunk (cas_no, text_hash);

-- 固定一行（id=1）：向量索引的身份信息与失败暂停状态
CREATE TABLE msds_index_meta (
    id             INT PRIMARY KEY,
    model_id       VARCHAR(64),
    dimension      INT,
    chunk_version  INT,
    schema_version INT,
    updated_at     DATETIME(6),
    paused_at      DATETIME(6),
    paused_reason  VARCHAR(255)
);

CREATE TABLE app_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    enabled       BOOLEAN      NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    CONSTRAINT uk_app_user_username UNIQUE (username)
);

CREATE INDEX idx_app_user_role ON app_user (role);
