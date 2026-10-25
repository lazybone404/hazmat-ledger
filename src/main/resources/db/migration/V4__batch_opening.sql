-- =====================================================================
-- 开封管理。
--
-- 问题的来源：批次上的 expire_date 是**供应商标注的包装有效期**，
-- 前提是「未开封、按规定条件贮存」。试剂一旦开封，稳定性由开封那一刻重新起算
-- （甲醇吸湿、过氧化氢分解、乙醚生成过氧化物），台账上写着还有半年有效期，
-- 这一瓶可能早就不能用了。
--
-- 做法：把「实际可用截止日」物化成 chemical_batch.usable_until。
--   - 入库时 = expire_date（还没开封，按包装有效期）
--   - 开封时 = min(expire_date, 开封日 + chemical.post_open_days)
-- 出库过滤、库存视图、效期预警、报表**统一只读这一列**。
-- 不让各处自己用「expire_date 加上开封天数」现算，是因为那等于把同一套判定
-- 复制到四个地方，迟早出现「库存视图说可用、出库说不可用」。
--
-- post_open_days 放在试剂档案上：它是化学性质，同一试剂的每一批都一样。
-- 为空表示不做开封后追踪（如固体试剂、惰性物质），此时开封只留痕、不改可用期。
--
-- 为什么 usable_until 允许 NULL：给已有行补值要 UPDATE，而「设置 NOT NULL」
-- 的语法 H2 与 MySQL 8 不通用（H2 用 ALTER COLUMN ... SET NOT NULL，
-- MySQL 用 MODIFY COLUMN ... NOT NULL）。本脚本优先保证两个库都能跑通：
-- 列允许 NULL，但回填之后每行都有值，且实体的 @PrePersist 会兜底补上，
-- 不会产生新的 NULL。真实部署若要收紧，在 MySQL 上单独执行 MODIFY 即可。
-- =====================================================================

ALTER TABLE chemical ADD COLUMN post_open_days INT;

ALTER TABLE chemical_batch ADD COLUMN opened_at DATETIME(6);
ALTER TABLE chemical_batch ADD COLUMN opened_by VARCHAR(100);
ALTER TABLE chemical_batch ADD COLUMN usable_until DATE;

-- 已有批次全部补齐：没有开封记录的批次，可用截止日就是包装有效期
UPDATE chemical_batch SET usable_until = expire_date;

-- 效期预警：WHERE usable_until <= ? AND available_quantity > 0
CREATE INDEX idx_batch_usable_until ON chemical_batch (usable_until);
