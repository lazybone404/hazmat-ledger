package com.lazybone.hazmat.repository;

import com.lazybone.hazmat.entity.StockLedger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface StockLedgerRepository extends JpaRepository<StockLedger, Long> {

    List<StockLedger> findByBatchIdOrderByCreatedAtAscIdAsc(Long batchId);

    List<StockLedger> findByRefTypeAndRefId(String refType, Long refId);

    /**
     * 某试剂每个批次的**最后一笔**流水。
     *
     * 对账要看的就是它：{@code balanceAfter} 是「这一笔发生后的余额」，
     * 所以每个批次最后一笔的余额，应当与批次表当前的 {@code availableQuantity} 相等。
     * 取 id 最大的那条而不是按时间排序取第一条——同一次操作里可能连写多笔，
     * id 是这里唯一严格递增的字段。
     *
     * 用子查询一次拿全，而不是逐个批次查：后者是典型的 N+1。
     */
    @Query("""
            select l from StockLedger l
             where l.id in (
                 select max(l2.id) from StockLedger l2
                  where l2.chemicalId = :chemicalId
                  group by l2.batchId)
            """)
    List<StockLedger> findLatestPerBatch(@Param("chemicalId") Long chemicalId);

    /**
     * 某试剂全部流水的变动量之和，带符号（入库为正，出库/报废/盘亏为负）。
     *
     * 它与「所有批次可用量之和」是同一件事的两种记法：一种是逐笔累加，
     * 一种是当前快照。两者对不上，说明中间有笔流水漏记或重复。
     */
    @Query("select coalesce(sum(l.quantity), 0) from StockLedger l where l.chemicalId = :chemicalId")
    BigDecimal sumQuantityByChemical(@Param("chemicalId") Long chemicalId);

    /**
     * 某试剂的出入库流水，分页。
     *
     * 走 chemicalId 而不是 batchId：对账时关心的是「这个试剂发生过什么」，
     * 批次只是其中一笔的细节。索引 idx_ledger_batch 帮不上忙，需要
     * idx_ledger_chem（见 V1 迁移脚本）。
     */
    Page<StockLedger> findByChemicalId(Long chemicalId, Pageable pageable);
}
