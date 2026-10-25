package com.lazybone.hazmat.report;

import com.lazybone.hazmat.common.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 统计报表。只读，登录即可访问——库存与效期分布对实验室内所有人都有用。
 *
 * <b>这里返回 {@code List} 而不是分页结构，是刻意的例外。</b>
 * 结果行数是「分类 × 单位」的组合数，量级是十几条，翻页只会增加前端复杂度。
 * 一旦某个报表的行数可能随数据量无限增长（比如按试剂逐条列出），
 * 就必须改回 {@link com.lazybone.hazmat.common.PageResponse}。
 */
@RestController
@RequestMapping("/api/reports")
@Validated
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * 库存汇总：按分类 + 单位给出试剂数、批次数、总量与效期分布。
     *
     * @param withinDays 预警窗口，含今天；0 表示只看已过期
     */
    @GetMapping("/stock-summary")
    public ApiResponse<List<StockSummaryRow>> stockSummary(
            @Min(0) @Max(365) @RequestParam(defaultValue = "30") int withinDays) {
        return ApiResponse.ok(reportService.stockSummary(withinDays));
    }

    /** 出入库趋势：按自然日 + 变更类型汇总，最近的日期在前 */
    @GetMapping("/flow-trend")
    public ApiResponse<List<FlowTrendRow>> flowTrend(
            @Min(1) @Max(365) @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(reportService.flowTrend(days));
    }
}
