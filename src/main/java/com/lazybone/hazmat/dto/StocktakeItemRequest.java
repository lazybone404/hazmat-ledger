package com.lazybone.hazmat.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 录入一条实盘量。
 *
 * 允许填 0（整瓶用完了、实物不见了），但不允许负数——负数在库存里没有物理意义，
 * 出现负数一定是录入错误，应该在入口挡掉而不是让它进台账。
 */
public record StocktakeItemRequest(
        @NotNull(message = "实盘量不能为空")
        @DecimalMin(value = "0", message = "实盘量不能为负数")
        @DecimalMax(value = "999999999.999", message = "实盘量超出可记录范围")
        BigDecimal actualQuantity,

        @Size(max = 500, message = "差异说明不能超过 500 字")
        String note) {
}
