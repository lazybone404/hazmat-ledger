package com.lazybone.hazmat.repository;

import com.lazybone.hazmat.entity.Stocktake;
import com.lazybone.hazmat.entity.StocktakeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StocktakeRepository
        extends JpaRepository<Stocktake, Long>, JpaSpecificationExecutor<Stocktake> {

    Optional<Stocktake> findByStocktakeNo(String stocktakeNo);

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
