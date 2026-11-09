package com.lazybone.hazmat.controller;

import com.lazybone.hazmat.common.ApiResponse;
import com.lazybone.hazmat.common.BusinessException;
import com.lazybone.hazmat.common.PageQuery;
import com.lazybone.hazmat.common.PageResponse;
import com.lazybone.hazmat.dto.OpsAlertView;
import com.lazybone.hazmat.ops.AlertService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Set;

/**
 * 运维告警查询与解决，限 ADMIN。
 *
 * <p>没有外部通知通道（邮件/短信/webhook），告警只在应用内可见；
 * 这里的接口是它唯一的出口。定时任务写、人来看和解决，形成闭环。
 */
@RestController
@RequestMapping("/api/ops/alerts")
@Validated
@PreAuthorize("hasRole('ADMIN')")
public class OpsAlertController {

    private static final Set<String> ALERT_SORTABLE = Set.of("id", "lastSeenAt", "firstSeenAt", "severity");

    private final AlertService alertService;

    public OpsAlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    /**
     * 分页查询。{@code status} 用 OPEN / RESOLVED 而不是直接暴露时间字段：
     * 调用方关心的是「还没处理」，不是 {@code resolvedAt} 是否为空。
     */
    @GetMapping
    public ApiResponse<PageResponse<OpsAlertView>> page(
            @RequestParam(required = false) String status,
            @Size(max = 40) @RequestParam(required = false) String type,
            @Min(0) @RequestParam(defaultValue = "0") int page,
            @Min(1) @Max(100) @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        Boolean resolved = parseStatus(status);
        Sort order = PageQuery.parse(sort, ALERT_SORTABLE, Sort.by(Sort.Direction.DESC, "lastSeenAt"));
        Pageable pageable = PageQuery.of(page, size, order);
        return ApiResponse.ok(PageResponse.from(
                alertService.page(resolved, type, pageable), OpsAlertView::from));
    }

    @PostMapping("/{id}/resolve")
    public ApiResponse<OpsAlertView> resolve(@PathVariable Long id,
                                             @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(OpsAlertView.from(alertService.resolve(id, jwt.getSubject())));
    }

    private static Boolean parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "OPEN" -> Boolean.FALSE;
            case "RESOLVED" -> Boolean.TRUE;
            default -> throw new BusinessException("status 只能是 OPEN 或 RESOLVED");
        };
    }
}
