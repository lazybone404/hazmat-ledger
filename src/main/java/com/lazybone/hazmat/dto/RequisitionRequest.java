package com.lazybone.hazmat.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 提交领用申请。
 *
 * 没有 applicant 字段：申请人来自登录凭证，不能由请求体指定。
 * 允许客户端传申请人，等于允许任何人冒名提交——这类字段不该出现在入参 DTO 里。
 */
public record RequisitionRequest(
        @NotNull(message = "试剂不能为空") @Positive(message = "试剂 id 必须是正整数") Long chemicalId,
        @NotNull(message = "领用量不能为空") @DecimalMin(value = "0.001", message = "领用量必须大于 0") BigDecimal quantity,
        @NotBlank(message = "用途不能为空") @Size(max = 500) String purpose) {
}
