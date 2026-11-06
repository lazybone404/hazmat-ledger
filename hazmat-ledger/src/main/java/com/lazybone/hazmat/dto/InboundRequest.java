package com.lazybone.hazmat.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 入库：给某试剂新建一个批次 */
public record InboundRequest(
        @NotBlank(message = "批次号不能为空") @Size(max = 50) String batchNo,
        @NotNull(message = "入库量不能为空")
        @DecimalMin(value = "0.001", message = "入库量必须大于 0")
        @DecimalMax(value = "999999999.999", message = "入库量超出可记录范围")
        BigDecimal quantity,
        @NotBlank(message = "存放位置不能为空") @Size(max = 100) String location,
        @NotNull(message = "有效期不能为空") LocalDate expireDate) {
}
