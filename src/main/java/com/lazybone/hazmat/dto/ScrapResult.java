package com.lazybone.hazmat.dto;

import java.math.BigDecimal;

/**
 * 一次报废的结果。
 *
 * 不退批次实体，是因为扣减走的是批量 update，持久化上下文里的实体仍是扣减前的旧值；
 * 直接返回重新读出的余额更可靠，也避免调用方误用旧数据。
 */
public record ScrapResult(
        Long batchId,
        String batchNo,
        BigDecimal scrappedQuantity,
        BigDecimal remainingQuantity,
        String reason,
        String operator) {
}
