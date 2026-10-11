package com.lazybone.hazmat.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 建盘点单。
 *
 * {@code chemicalIds} 为空表示盘点**全部**仍有库存的批次——这是最常见的用法：
 * 每月对全库盘一次。传了 ids 就只盘这几种试剂（按柜子、按品类分批盘）。
 *
 * 不传 {@code createdBy}：创建人来自 token，理由和其它接口一致。
 */
public record StocktakeCreateRequest(
        List<@Positive(message = "试剂 id 必须为正数") Long> chemicalIds,

        @Size(max = 500, message = "备注不能超过 500 字")
        String remark) {
}
