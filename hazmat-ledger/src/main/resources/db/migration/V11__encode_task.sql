-- MSDS 编码任务表（D18）。
--
-- 任务表是事实源，RabbitMQ 只是唤醒信号：消息体只带 taskId，
-- 消费端回表读版本、尝试次数和状态。这样 broker 丢消息、应用重启、
-- 重复投递都不会让任务本身消失。
--
-- 先插任务行、再发消息；发送失败时任务保持 PENDING，由定时 relay 补发。
-- 状态：PENDING / RUNNING / DONE / FAILED / DEAD。
--
-- index_version 允许为空：兼容版本化之前 index_version IS NULL 的历史块。
-- 语法只使用 H2 与 MySQL 8 都支持的写法。

CREATE TABLE msds_encode_task (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    index_version  VARCHAR(64),
    status         VARCHAR(16)  NOT NULL,
    attempts       INT          NOT NULL DEFAULT 0,
    max_attempts   INT          NOT NULL DEFAULT 3,
    worker_id      VARCHAR(64),
    last_error     VARCHAR(1000),
    claimed_at     DATETIME(6),
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    finished_at    DATETIME(6)
);

-- relay 按「状态 + 最后更新时间」扫待补发任务
CREATE INDEX idx_msds_encode_task_status ON msds_encode_task (status, updated_at);

-- 同一索引版本只复用未完成任务；版本切换后旧任务不再被选中
CREATE INDEX idx_msds_encode_task_version ON msds_encode_task (index_version, status);
