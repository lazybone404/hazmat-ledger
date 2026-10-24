-- =====================================================================
-- V3：库存盘点
--
-- 盘点的作用是把「系统账上的可用量」和「柜子里的实物」对齐。
-- 在此之前系统只有流水（入/出/报废），没有任何机制去核对两者是否一致，
-- 账会随时间的漏登记慢慢漂移，而所有下游（预警、报表、MSDS 提示）
-- 都建立在这个已经漂移的数字上。
--
-- 结构上分两张表：单据 + 明细。
--   * 明细一条对应一个**批次**，不是试剂：库存记在批次上，盘亏也要落到具体哪一瓶。
--   * book_quantity 是**创建盘点单那一刻的账面量快照**，不是确认时的值。
--     实盘量的语义是「相对于我数的那一刻」，账面值必须跟着冻结；
--     确认时用条件更新比对它，对不上就说明盘点期间库存动过，必须重新盘。
--
-- 语法约束同 V1：只用 H2 与 MySQL 8 都支持的写法，不写 ENGINE / CHARSET。
-- =====================================================================

CREATE TABLE stocktake (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    stocktake_no VARCHAR(40)  NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    remark       VARCHAR(500),
    created_by   VARCHAR(100) NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    submitted_by VARCHAR(100),
    submitted_at DATETIME(6),
    confirmed_by VARCHAR(100),
    confirmed_at DATETIME(6),
    version      BIGINT       NOT NULL,
    CONSTRAINT uk_stocktake_no UNIQUE (stocktake_no)
);

-- 列表按状态筛选 + 时间倒序，与分页索引的做法一致（见 V2）
CREATE INDEX idx_stocktake_status_created ON stocktake (status, created_at);

CREATE TABLE stocktake_item (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    stocktake_id    BIGINT        NOT NULL,
    batch_id        BIGINT        NOT NULL,
    chemical_id     BIGINT        NOT NULL,
    -- 批次号快照：台账要能回答「当时盘的是哪一瓶」，不能依赖批次表被改过
    batch_no        VARCHAR(50)   NOT NULL,
    book_quantity   DECIMAL(12, 3) NOT NULL,
    actual_quantity DECIMAL(12, 3),
    diff_quantity   DECIMAL(12, 3),
    note            VARCHAR(500)
);

CREATE INDEX idx_stocktake_item_stocktake ON stocktake_item (stocktake_id);
-- 建单时要查「这个批次是否已经在别的未完成盘点单里」，走这条索引
CREATE INDEX idx_stocktake_item_batch ON stocktake_item (batch_id);
