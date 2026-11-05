package com.lazybone.hazmat.controller;

import com.lazybone.hazmat.common.ApiResponse;
import com.lazybone.hazmat.dto.IncompatibilityReport;
import com.lazybone.hazmat.service.IncompatibilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 禁忌混放检查。读接口只要求登录，与库存、MSDS 安全信息的可见范围一致。
 *
 * 单个试剂的结果放在 {@code /api/chemicals/{id}/incompatibilities}，
 * 与「某个试剂的库存/安全信息」保持同一套资源路径。
 */
@RestController
@RequestMapping("/api/incompatibilities")
public class IncompatibilityController {

    private final IncompatibilityService incompatibilityService;

    public IncompatibilityController(IncompatibilityService incompatibilityService) {
        this.incompatibilityService = incompatibilityService;
    }

    /**
     * 全实验室当前在库试剂的混放检查。
     *
     * 返回里的 {@code uncovered} 是「规则表没有记录」的显式清单，
     * 不是安全清单；两者必须由前端分开展示。
     */
    @GetMapping
    public ApiResponse<IncompatibilityReport> report() {
        return ApiResponse.ok(incompatibilityService.checkAll());
    }
}
