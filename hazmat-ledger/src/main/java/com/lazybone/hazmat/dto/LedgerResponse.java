package com.lazybone.hazmat.dto;

import com.lazybone.hazmat.entity.StockLedger;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一条出入库流水。
 *
 * {@code quantity} 带符号：入库为正、出库与报废为负，
 * 前端直接累加就能和 {@code balanceAfter} 对上，不用再按类型判断正负。
 */
public record LedgerResponse(
        Long id,
        Long batchId,
        String changeType,
        BigDecimal quantity,
        BigDecimal balanceAfter,
        String refType,
        Long refId,
        String operator,
        LocalDateTime createdAt) {

    public static LedgerResponse from(StockLedger l) {
        return new LedgerResponse(l.getId(), l.getBatchId(), l.getChangeType(),
                l.getQuantity(), l.getBalanceAfter(), l.getRefType(), l.getRefId(),
                l.getOperator(), l.getCreatedAt());
    }
}
