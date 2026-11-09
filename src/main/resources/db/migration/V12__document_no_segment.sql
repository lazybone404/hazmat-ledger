-- 业务单号号段（M1）。
--
-- 进程内自增在多实例部署时会重复：每个实例从各自随机位置起算，撞上唯一索引
-- 只能靠调用方重试。号段把分配点收敛到数据库：一次取一段，段内由进程自增。
--
-- 号段允许跳号：业务事务回滚时，已经分配给它的号不会退回。
-- 单号只要求唯一、可读、不要求连续。
--
-- 语法只使用 H2 与 MySQL 8 都支持的写法。

CREATE TABLE document_no_segment (
    prefix     VARCHAR(8)  PRIMARY KEY,
    next_value BIGINT      NOT NULL,
    updated_at DATETIME(6) NOT NULL
);

INSERT INTO document_no_segment (prefix, next_value, updated_at) VALUES ('REQ', 1, CURRENT_TIMESTAMP);
INSERT INTO document_no_segment (prefix, next_value, updated_at) VALUES ('ST', 1, CURRENT_TIMESTAMP);
INSERT INTO document_no_segment (prefix, next_value, updated_at) VALUES ('SCRAP', 1, CURRENT_TIMESTAMP);
