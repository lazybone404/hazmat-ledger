package com.lazybone.hazmat.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建试剂档案的请求体。
 *
 * {@code postOpenDays} 可空：为空表示这个试剂不做开封后追踪（固体、惰性物质），
 * 开封只留痕、不缩短可用期。给了值就必须是正数——0 或负数会让开封后的批次立刻失效，
 * 那不是一个有意义的配置。
 */
public record ChemicalRequest(
        @NotBlank(message = "CAS 号不能为空") @Size(max = 20) String casNo,
        @NotBlank(message = "试剂名称不能为空") @Size(max = 200) String name,
        @NotBlank(message = "危险类别不能为空") @Size(max = 50) String category,
        @NotBlank(message = "危险等级不能为空") @Size(max = 20) String hazardLevel,
        @NotBlank(message = "计量单位不能为空") @Size(max = 20) String unit,
        @Size(max = 100) String spec,
        @Size(max = 300) String msdsUrl,
        @Min(value = 1, message = "开封后可用天数必须大于 0")
        @Max(value = 3650, message = "开封后可用天数不能超过 3650 天")
        Integer postOpenDays) {
}
