-- 盘点复核（M1）。
--
-- 盘点制度要求「盘的人」和「核的人」分开。当前版本默认不强制，
-- 但状态机和字段先补齐：打开 require-review 后必须先复核再确认。
--
-- 历史盘点单没有复核人，新增列全部可空，不回填，迁移不会因旧行失败。
-- 语法只使用 H2 与 MySQL 8 都支持的写法。

ALTER TABLE stocktake ADD COLUMN reviewed_by VARCHAR(100);
ALTER TABLE stocktake ADD COLUMN reviewed_at DATETIME(6);
ALTER TABLE stocktake ADD COLUMN review_comment VARCHAR(500);

CREATE INDEX idx_stocktake_status_submitted ON stocktake (status, submitted_at);
