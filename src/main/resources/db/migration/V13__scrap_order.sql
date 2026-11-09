-- 报废审批单（M1）。
--
-- 原先只有管理员直接扣减的应急入口，缺少「申请 → 审批 → 扣减」的单据流程。
-- 常规报废走这张表；管理员直接报废保留为应急入口。
--
-- 审批通过和扣减必须在同一事务里完成：审批只是状态变更、库存没动，
-- 或者库存动了、单据没批，都会让台账与单据对不上。
--
-- batch_no 存快照：台账要回答「当时报废的是哪一瓶」，不能依赖批次表后来被改过。
-- 语法只使用 H2 与 MySQL 8 都支持的写法。

CREATE TABLE scrap_order (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    scrap_no       VARCHAR(40)   NOT NULL,
    chemical_id    BIGINT        NOT NULL,
    batch_id       BIGINT        NOT NULL,
    batch_no       VARCHAR(50)   NOT NULL,
    quantity       DECIMAL(12, 3) NOT NULL,
    reason         VARCHAR(500)  NOT NULL,
    status         VARCHAR(20)   NOT NULL,
    applicant      VARCHAR(100)  NOT NULL,
    reviewer       VARCHAR(100),
    review_comment VARCHAR(500),
    created_at     DATETIME(6)   NOT NULL,
    reviewed_at    DATETIME(6),
    version        BIGINT        NOT NULL,
    CONSTRAINT uk_scrap_order_no UNIQUE (scrap_no)
);

CREATE INDEX idx_scrap_order_status_created ON scrap_order (status, created_at);
CREATE INDEX idx_scrap_order_applicant_created ON scrap_order (applicant, created_at);
