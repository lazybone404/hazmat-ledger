-- 编码任务保留清理（M2）。
--
-- 保留任务只删 DONE / DEAD 且 finished_at 早于保留期的行。
-- 这条索引让清理任务按「终态 + 完成时间」直接定位，不扫全表。
-- 语法只使用 H2 与 MySQL 8 都支持的写法。

CREATE INDEX idx_msds_encode_task_finished ON msds_encode_task (status, finished_at);
