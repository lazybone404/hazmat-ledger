package com.lazybone.hazmat.dto;

import com.lazybone.hazmat.entity.Requisition;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record RequisitionResponse(
        Long id,
        String reqNo,
        String applicant,
        Long chemicalId,
        BigDecimal quantity,
        String purpose,
        String status,
        String reviewer,
        String reviewComment,
        /** 双人复核里第一位审批人。状态是 PENDING_SECOND 时，靠它知道还差谁之外的那个人 */
        String firstReviewer,
        Long issuedBatchId,
        BigDecimal issuedQuantity,
        LocalDateTime createdAt,
        LocalDateTime reviewedAt,
        LocalDateTime issuedAt,
        LocalDateTime closedAt) {

    public static RequisitionResponse from(Requisition r) {
        return new RequisitionResponse(r.getId(), r.getReqNo(), r.getApplicant(), r.getChemicalId(),
                r.getQuantity(), r.getPurpose(), r.getStatus().name(), r.getReviewer(),
                r.getReviewComment(), r.getFirstReviewer(), r.getIssuedBatchId(), r.getIssuedQuantity(),
                r.getCreatedAt(), r.getReviewedAt(), r.getIssuedAt(), r.getClosedAt());
    }
}
