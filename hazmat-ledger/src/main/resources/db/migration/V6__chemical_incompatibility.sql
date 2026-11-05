-- 禁忌混放关系表：两种试剂不能放在一起，以及这条结论的依据。
--
-- 用结构化二元表而不是 RAG/图数据库（见 docs/架构决策.md D14）：
-- 「A 与 B 禁忌」是二元关系，做混放检查只需要两两比对；
-- 检索负责「这条禁忌出自哪段原文」，不负责判定哪两种试剂冲突。
--
-- 两个设计点：
--   1. 主键仍是自增 id，业务唯一键是 (cas_a, cas_b)。
--      用 CAS 号而不是 chemical.id 关联：禁忌是化学品本身的化学性质，
--      不随某条台账档案的增删而改变；也允许规则先于档案存在。
--   2. 约定 cas_a < cas_b，并由 CHECK 约束兜底。这样 (A,B) 与 (B,A)
--      只可能存一条，查表时也不用两个方向各查一次。
--
-- 本表只存「有依据的禁忌对」，不存「确认可以混放」。
-- 表里查不到的组合必须在接口里显式标成「未覆盖」，不能默认安全。
CREATE TABLE chemical_incompatibility (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    cas_a      VARCHAR(20)  NOT NULL,
    cas_b      VARCHAR(20)  NOT NULL,
    reason     VARCHAR(500) NOT NULL,
    source     VARCHAR(300) NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    CONSTRAINT uk_incompatibility_pair UNIQUE (cas_a, cas_b),
    CONSTRAINT ck_incompatibility_order CHECK (cas_a < cas_b)
);

-- 按单个试剂查关系时用到；(cas_a, cas_b) 的唯一索引只覆盖了第一个方向
CREATE INDEX idx_incompatibility_cas_b ON chemical_incompatibility (cas_b);
