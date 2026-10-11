package com.lazybone.hazmat.dto;

import com.lazybone.hazmat.entity.StocktakeItem;

import java.math.BigDecimal;

/**
 * 盘点明细。
 *
 * {@code diffQuantity} 在建单/录入阶段是 null（还不知道差异），提交后才算出来；
 * 不用 0 占位，因为「还没数」和「数了、刚好相等」是两件事，
 * 前端要据此区分「待录入」和「无差异」。
 */
public record StocktakeItemResponse(
        Long itemId,
        Long batchId,
        Long chemicalId,
        String chemicalName,
        String unit,
        String batchNo,
        BigDecimal bookQuantity,
        BigDecimal actualQuantity,
        BigDecimal diffQuantity,
        String note) {

    public static StocktakeItemResponse from(StocktakeItem item, String chemicalName, String unit) {
        return new StocktakeItemResponse(
                item.getId(), item.getBatchId(), item.getChemicalId(),
                chemicalName == null ? "未知试剂" : chemicalName,
                unit == null ? "" : unit,
                item.getBatchNo(), item.getBookQuantity(),
                item.getActualQuantity(), item.getDiffQuantity(), item.getNote());
    }
}
