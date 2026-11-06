package com.lazybone.hazmat.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 报废：从某个批次扣掉不可用的量。
 *
 * 报废必须写明原因——台账要能回答「这批东西为什么少了」，
 * 只记一个负数流水不足以说明是过期、破损还是实验废弃。
 */
public record ScrapRequest(
        @NotNull(message = "报废量不能为空")
        @DecimalMin(value = "0.001", message = "报废量必须大于 0")
        @DecimalMax(value = "999999999.999", message = "报废量超出可记录范围")
        BigDecimal quantity,

        @NotBlank(message = "报废原因不能为空")
        @Size(max = 200, message = "报废原因不能超过 200 字")
        String reason) {
}
