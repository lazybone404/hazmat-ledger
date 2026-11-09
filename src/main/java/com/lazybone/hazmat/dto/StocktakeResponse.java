package com.lazybone.hazmat.dto;

import com.lazybone.hazmat.entity.Stocktake;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 盘点单。明细直接内嵌：一张单的批次数量是「一次盘点的范围」，
 * 量级几十条，和申请单的时间线一样，分页只会让前端多一次往返。
 */
public record StocktakeResponse(
        String stocktakeNo,
        String status,
        String remark,
        String createdBy,
        LocalDateTime createdAt,
        String submittedBy,
        LocalDateTime submittedAt,
        String reviewedBy,
        LocalDateTime reviewedAt,
        String reviewComment,
        String confirmedBy,
        LocalDateTime confirmedAt,
        /** 有差异的条数；尚未全部录入时为 null */
        Long diffCount,
        List<StocktakeItemResponse> items) {

    public static StocktakeResponse from(Stocktake s, List<StocktakeItemResponse> items) {
        Long diffCount = items.stream()
                .map(StocktakeItemResponse::diffQuantity)
                .anyMatch(d -> d == null)
                ? null
                : items.stream().filter(i -> i.diffQuantity().signum() != 0).count();
        return new StocktakeResponse(
                s.getStocktakeNo(), s.getStatus().name(), s.getRemark(),
                s.getCreatedBy(), s.getCreatedAt(),
                s.getSubmittedBy(), s.getSubmittedAt(),
                s.getReviewedBy(), s.getReviewedAt(), s.getReviewComment(),
                s.getConfirmedBy(), s.getConfirmedAt(),
                diffCount, items);
    }
}
