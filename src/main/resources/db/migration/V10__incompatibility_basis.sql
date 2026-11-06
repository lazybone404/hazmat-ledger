-- 禁忌规则的结构化依据：把「source 一行文本」拆成可查询、可统计的字段。
-- 现有 51 对全部来自仓库内 MSDS 第 10 节，迁移只回填状态；
-- doc/clause/excerpt 由 IncompatibilitySeedRunner 启动时按种子文件补齐（按对匹配，不重复插入）。

ALTER TABLE chemical_incompatibility ADD COLUMN source_type VARCHAR(16) DEFAULT 'MSDS' NOT NULL;
ALTER TABLE chemical_incompatibility ADD COLUMN source_doc VARCHAR(200);
ALTER TABLE chemical_incompatibility ADD COLUMN source_clause VARCHAR(64);
ALTER TABLE chemical_incompatibility ADD COLUMN source_excerpt VARCHAR(1000);
ALTER TABLE chemical_incompatibility ADD COLUMN verification_status VARCHAR(16) DEFAULT 'PENDING' NOT NULL;

-- 历史数据都是 MSDS 来源；没有条款级依据的规则默认 PENDING，不能用「有规则」冒充「已核实」
UPDATE chemical_incompatibility
SET verification_status = 'VERIFIED'
WHERE source_type = 'MSDS' AND source LIKE 'MSDS%';
