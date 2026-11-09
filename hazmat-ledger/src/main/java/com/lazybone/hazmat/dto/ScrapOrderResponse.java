package com.lazybone.hazmat.dto;

import com.lazybone.hazmat.entity.ScrapOrder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ScrapOrderResponse(
        Long id,
        String scrapNo,
        Long chemicalId,
        Long batchId,
        String batchNo,
        BigDecimal quantity,
        String reason,
        String status,
        String applicant,
        String reviewer,
        String reviewComment,
        LocalDateTime createdAt,
        LocalDateTime reviewedAt) {

    public static ScrapOrderResponse from(ScrapOrder o) {
        return new ScrapOrderResponse(o.getId(), o.getScrapNo(), o.getChemicalId(), o.getBatchId(),
                o.getBatchNo(), o.getQuantity(), o.getReason(), o.getStatus().name(), o.getApplicant(),
                o.getReviewer(), o.getReviewComment(), o.getCreatedAt(), o.getReviewedAt());
    }
}
