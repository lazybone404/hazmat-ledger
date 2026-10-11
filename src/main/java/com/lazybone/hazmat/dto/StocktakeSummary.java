package com.lazybone.hazmat.dto;

import com.lazybone.hazmat.entity.Stocktake;

import java.time.LocalDateTime;

/**
 * 盘点单列表项：只有单据头，不含明细。
 *
 * <b>列表为什么不带明细。</b>一张盘点单可能是「全部有库存的批次」，几十条明细；
 * 列表要翻页，每条都带明细等于把明细表重复读一遍，而且前端在列表页也只关心
 * 「哪张单还没盘完」。要看差异就进详情。
 *
 * 所以这里也没有 {@code diffCount}：它依赖明细，放进列表就会退化成 N+1。
 */
public record StocktakeSummary(
        String stocktakeNo,
        String status,
        String remark,
        String createdBy,
        LocalDateTime createdAt,
        String submittedBy,
        LocalDateTime submittedAt,
        String confirmedBy,
        LocalDateTime confirmedAt) {

    public static StocktakeSummary from(Stocktake s) {
        return new StocktakeSummary(
                s.getStocktakeNo(), s.getStatus().name(), s.getRemark(),
                s.getCreatedBy(), s.getCreatedAt(),
                s.getSubmittedBy(), s.getSubmittedAt(),
                s.getConfirmedBy(), s.getConfirmedAt());
    }
}
