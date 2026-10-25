package com.lazybone.hazmat.report;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 库存汇总报表的一行：一个「分类 + 单位」组合的合计。
 *
 * 为什么按单位分组而不是把量全加起来：mL 和 g 不能相加。
 * 报表里出现「总库存 12345」这种数字，除了好看没有任何意义。
 *
 * 用普通类而不是 record：MyBatis 默认走无参构造 + setter 映射，
 * record 需要额外开 `arg-name-based-constructor-auto-mapping` 且依赖编译保留参数名。
 * 报表 DTO 只在服务端内部使用，不值得为语法糖增加这一层隐式依赖。
 */
@Data
public class StockSummaryRow {

    private String category;
    private String unit;

    /** 该分类下有多少个试剂档案 */
    private long chemicalCount;

    /** 有库存的批次数（可用量为 0 的批次不计） */
    private long batchCount;

    /** 可用总量（含已过期批次，仅用于对账口径） */
    private BigDecimal totalAvailable;

    /** 其中在预警窗口内到期的量 */
    private BigDecimal expiringQuantity;

    /** 其中已经过期的量 */
    private BigDecimal expiredQuantity;
}
