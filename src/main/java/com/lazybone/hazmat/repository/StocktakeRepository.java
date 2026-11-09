package com.lazybone.hazmat.repository;

import com.lazybone.hazmat.entity.Stocktake;
import com.lazybone.hazmat.entity.StocktakeStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StocktakeRepository
        extends JpaRepository<Stocktake, Long>, JpaSpecificationExecutor<Stocktake> {

    Optional<Stocktake> findByStocktakeNo(String stocktakeNo);

    /**
     * 状态变更与实盘录入共用同一把行锁。
     *
     * <p>录入实盘量只改明细行、提交只改单头，两者不在同一行上，{@code @Version}
     * 拦不住「保存请求晚于提交请求提交」这种交错。先用 {@code SELECT ... FOR UPDATE}
     * 锁住单头，再读明细、判状态，保存与提交就串行化了：后到的那个要么看到新状态被拒，
     * 要么在锁内看到最新的实盘量。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Stocktake s where s.stocktakeNo = :stocktakeNo")
    Optional<Stocktake> findByStocktakeNoForUpdate(@Param("stocktakeNo") String stocktakeNo);

    /**
     * 超时提醒用：指定状态且提交时间早于阈值。
     *
     * <p>用 {@code submittedAt} 而不是「最后更新时间」：项目里没有维护 updatedAt，
     * 而盘点停在某个状态不动的起点就是提交那一刻。{@code REVIEWED} 的单子同样按
     * 提交时间算——它提交后一直没人推进，本来就是要提醒的对象。
     */
    List<Stocktake> findByStatusInAndSubmittedAtBefore(
            Collection<StocktakeStatus> statuses, LocalDateTime cutoff);
}
