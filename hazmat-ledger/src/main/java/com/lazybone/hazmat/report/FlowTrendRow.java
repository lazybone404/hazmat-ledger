package com.lazybone.hazmat.report;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 出入库趋势的一行：某一天、某种变更类型的合计。
 *
 * {@code totalQuantity} 是该类型记录的符号之和：入库为正，出库与报废为负。
 * 不在 SQL 里取绝对值——需要净变化量时直接相加即可，返回绝对值反而丢信息。
 */
@Data
public class FlowTrendRow {

    private LocalDate statDate;

    /** IN / OUT / SCRAP */
    private String changeType;

    private long recordCount;

    private BigDecimal totalQuantity;
}
