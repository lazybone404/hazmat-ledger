package com.lazybone.hazmat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * MSDS 索引重建的数据库行锁，固定 {@code id=1} 一行。
 *
 * <p>表里只有这一行，值本身不重要，作用是给 {@code SELECT ... FOR UPDATE} 一个可锁的对象。
 * 锁随事务提交/回滚释放；实例崩溃时数据库连接断开，锁也会自动释放，
 * 不需要「检测持有者是否存活」这类额外逻辑。
 *
 * <p>它不是 fencing token：只保证同一时刻只有一个重建事务，不保证跨实例调度精确一次。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "msds_index_lock")
public class MsdsIndexLock {

    @Id
    private Integer id;

    /** 持有者标识，仅用于排查「上次是谁在重建」 */
    @Column(length = 64)
    private String owner;

    private LocalDateTime acquiredAt;
}
