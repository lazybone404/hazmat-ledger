package com.lazybone.hazmat.dto;

import jakarta.validation.constraints.Size;

/**
 * 盘点复核。复核是「看过差异、确认可以进入调账」的签字，不是通过/驳回二选一：
 * 发现问题时应当撤销盘点单，而不是把它驳回到某个中间状态——实盘量已经录下，
 * 退回去改的语义不清晰。所以请求体只有一个意见字段。
 */
public record StocktakeReviewRequest(
        @Size(max = 500) String comment) {
}
