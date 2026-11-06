-- 版本化索引切换（D11 / E8）。
-- 目标：重建期间旧版本继续可读，切换只改一个指针；出了问题能切回上一版。
-- 块表本身已经有 index_version 列（V7 预留），这里补全局指针与版本台账。

ALTER TABLE msds_index_meta ADD COLUMN active_version VARCHAR(64);
ALTER TABLE msds_index_meta ADD COLUMN previous_version VARCHAR(64);
ALTER TABLE msds_index_meta ADD COLUMN building_version VARCHAR(64);
ALTER TABLE msds_index_meta ADD COLUMN switched_at DATETIME(6);

-- 版本台账：ACTIVE 当前生效，PREVIOUS 回滚目标，RETIRED 已清理。
-- 重建在同一个事务里完成「写新版本块 + 改指针」，所以没有可观测的 BUILDING 状态；
-- building_version 列仍保留，给以后异步建索引留位置。
CREATE TABLE msds_index_version (
    version     VARCHAR(64) PRIMARY KEY,
    status      VARCHAR(16) NOT NULL,
    chunk_count INT         NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    switched_at DATETIME(6),
    retired_at  DATETIME(6)
);
