package com.lazybone.hazmat.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 报废申请。常规报废走这张单，管理员直接报废保留为应急入口。
 *
 * <p>创建时只校验「不超过当前可用量」，但真正扣减发生在审批那一刻：
 * 两张待审单可能都通过了创建校验，第二张审批时余量已经不够。这不是创建校验没用，
 * 而是它只能保证「提交那一刻」的事实；真正的守门人仍是扣减时的条件更新。
 */
public record ScrapOrderRequest(
        @NotNull(message = "试剂 id 不能为空")
        @Positive(message = "试剂 id 必须为正")
        Long chemicalId,

        @NotNull(message = "批次 id 不能为空")
        @Positive(message = "批次 id 必须为正")
        Long batchId,

        @NotNull(message = "报废量不能为空")
        @DecimalMin(value = "0.001", message = "报废量必须大于 0")
        @DecimalMax(value = "999999999.999", message = "报废量超出可记录范围")
        BigDecimal quantity,

        @NotBlank(message = "报废原因不能为空")
        @Size(max = 500, message = "报废原因不能超过 500 字")
        String reason) {
}
