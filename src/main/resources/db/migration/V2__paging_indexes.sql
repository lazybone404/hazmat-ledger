-- =====================================================================
-- 列表接口改为分页后的索引补充。
--
-- 分页查询的典型形态是「按某个条件过滤 + 按时间倒序 + 取一页」，
-- 单列索引只能帮到过滤，排序仍要额外排一次；复合索引能让过滤与排序
-- 都走同一个索引，避免大表上的 filesort。
--
-- 旧的单列索引 idx_req_applicant / idx_req_status 不删：
-- MySQL 与 H2 的 DROP INDEX 语法不同（MySQL 要带 ON table），
-- 为了跨库兼容保留它们。多出来的空间远小于维护两套脚本的成本。
-- =====================================================================

-- 试剂流水：WHERE chemical_id = ? ORDER BY created_at DESC, id DESC
CREATE INDEX idx_ledger_chem_created ON stock_ledger (chemical_id, created_at);

-- 普通用户只看自己的申请：WHERE applicant = ? ORDER BY created_at DESC
CREATE INDEX idx_req_applicant_created ON requisition (applicant, created_at);

-- 审批员按状态筛选：WHERE status = ? ORDER BY created_at DESC
CREATE INDEX idx_req_status_created ON requisition (status, created_at);
