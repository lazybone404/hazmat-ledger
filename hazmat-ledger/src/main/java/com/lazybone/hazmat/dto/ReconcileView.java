package com.lazybone.hazmat.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 对账结果：把「批次表上的余额」和「流水最后记的余额」摆在一起比。
 *
 * 这是台账「可对账」的落地接口。{@code StockLedger.balanceAfter} 就是为它存在的：
 * 没有这个冗余字段，对账得把全部历史累加一遍，而那依赖「一条都没漏」——
 * 漏了也看不出来。存了每笔余额之后，对账变成一次直接比较。
 *
 * 两个层次都返回：
 * <ul>
 *   <li><b>逐批次</b>：定位到具体哪一瓶。批次表余额 vs 该批次最后一笔流水的余额。</li>
 *   <li><b>汇总量</b>：批次可用量之和 vs 全部流水变动量之和。
 *       前者是快照，后者是逐笔累加，两条路算同一个东西。</li>
 * </ul>
 * 只要有一层对不上，{@code consistent} 就是 false。汇总量对得上但逐批次对不上，
 * 说明错在两个批次之间互抵了——这比总量不平更难发现，所以两层都要看。
 */
public record ReconcileView(
        Long chemicalId,
        String chemicalName,
        String unit,
        boolean consistent,
        BigDecimal totalAvailable,
        BigDecimal totalLedger,
        List<BatchBalance> batches) {

    /**
     * 单个批次的对账行。
     *
     * {@code ledgerBalance} 为 null 表示这个批次**一条流水都没有**——
     * 入库必写流水，所以这是异常状态，判为不一致。
     */
    public record BatchBalance(
            Long batchId,
            String batchNo,
            String location,
            BigDecimal availableQuantity,
            BigDecimal ledgerBalance,
            boolean consistent) {
    }
}
