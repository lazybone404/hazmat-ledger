package com.lazybone.hazmat.repository;

import com.lazybone.hazmat.entity.StocktakeItem;
import com.lazybone.hazmat.entity.StocktakeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface StocktakeItemRepository extends JpaRepository<StocktakeItem, Long> {

    List<StocktakeItem> findByStocktakeIdOrderByIdAsc(Long stocktakeId);

    /**
     * 这些批次里，有哪些已经落在**未完成**的盘点单里。
     *
     * 建单时用它挡住「同一批次被两张单同时盘」。这不是洁癖：两张单各自持有一份
     * bookQuantity 快照，先确认的那张一旦调账，后一张的条件更新必然失败——
     * 与其让用户盘完几十条才发现，不如建单时就说清楚。
     *
     * 条件更新本身仍然保留，因为「未完成」到「确认」之间库存还可能被出库改掉。
     */
    @Query("""
            select i.batchId from StocktakeItem i, Stocktake s
             where i.stocktakeId = s.id
               and s.status in :statuses
               and i.batchId in :batchIds
            """)
    List<Long> findBatchIdsInOpenStocktakes(@Param("batchIds") Collection<Long> batchIds,
                                            @Param("statuses") Collection<StocktakeStatus> statuses);
}
