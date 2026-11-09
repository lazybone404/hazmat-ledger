-- MSDS 索引重建的数据库行锁（M2）。
--
-- JVM 内的 ReentrantLock 只能挡住同一实例。多实例同时重建会写出两份块。
-- 这里用固定 id=1 的一行做 SELECT ... FOR UPDATE：锁随事务提交/回滚释放，
-- 实例崩溃时数据库连接断开，锁也会自动释放。
--
-- 它不是 fencing token：只保证同一时刻只有一个重建事务，不保证跨实例调度精确一次。
-- 语法只使用 H2 与 MySQL 8 都支持的写法。

CREATE TABLE msds_index_lock (
    id          INT PRIMARY KEY,
    owner       VARCHAR(64),
    acquired_at DATETIME(6)
);

INSERT INTO msds_index_lock (id) VALUES (1);
