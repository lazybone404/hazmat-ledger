-- 运维告警（M2）。
--
-- 定时任务发现问题后写这里，而不是只打日志：日志会被轮转、没人主动查，
-- 告警表可以分页查看、标记解决。
--
-- fingerprint 唯一：同一类故障重复扫描只累加 occurrences，不产生重复行。
-- 已解决的告警再次出现时，raise 会把 resolved_at/resolved_by 置空并重新打开。
-- 没有走外部通知通道：邮件/短信/webhook 需要额外凭据，不在本轮范围。
--
-- 语法只使用 H2 与 MySQL 8 都支持的写法。

CREATE TABLE ops_alert (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    alert_type    VARCHAR(40)   NOT NULL,
    severity      VARCHAR(16)   NOT NULL,
    fingerprint   VARCHAR(200)  NOT NULL,
    message       VARCHAR(1000) NOT NULL,
    occurrences   INT           NOT NULL DEFAULT 1,
    first_seen_at DATETIME(6)   NOT NULL,
    last_seen_at  DATETIME(6)   NOT NULL,
    resolved_at   DATETIME(6),
    resolved_by   VARCHAR(100),
    CONSTRAINT uk_ops_alert_fingerprint UNIQUE (fingerprint)
);

CREATE INDEX idx_ops_alert_resolved_last_seen ON ops_alert (resolved_at, last_seen_at);
