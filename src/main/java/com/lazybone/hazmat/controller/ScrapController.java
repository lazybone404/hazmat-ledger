package com.lazybone.hazmat.controller;

import com.lazybone.hazmat.common.ApiResponse;
import com.lazybone.hazmat.common.ForbiddenException;
import com.lazybone.hazmat.common.PageQuery;
import com.lazybone.hazmat.common.PageResponse;
import com.lazybone.hazmat.dto.ScrapOrderRequest;
import com.lazybone.hazmat.dto.ScrapOrderResponse;
import com.lazybone.hazmat.dto.ScrapReviewRequest;
import com.lazybone.hazmat.entity.ScrapOrder;
import com.lazybone.hazmat.entity.ScrapOrderStatus;
import com.lazybone.hazmat.service.ScrapOrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 报废审批单。
 *
 * <p>常规报废走这里：任何登录用户提交申请，审批员或管理员审批，通过时才扣库存。
 * 管理员直接扣减的应急入口仍在 {@code POST /api/chemicals/{id}/batches/{batchId}/scrap}，
 * 两条路径写出的台账用 {@code refType} 区分：{@code SCRAP} 是应急、{@code SCRAP_ORDER}
 * 是审批单。{@code refId} 的含义也随之不同（批次 id / 单据 id），报表不能只按 refId 关联。
 *
 * <p>审批做成独立子资源而不是 PUT 改状态：审批要角色、要前置状态、要写审批意见，
 * 合成一个通用更新接口会让前端可以构造任意状态跳转。
 */
@RestController
@RequestMapping("/api/scraps")
@Validated
public class ScrapController {

    /** 可排序字段白名单，理由见 {@link PageQuery#parse} */
    private static final Set<String> SCRAP_SORTABLE = Set.of("id", "createdAt", "status", "quantity");

    private final ScrapOrderService scrapOrderService;

    public ScrapController(ScrapOrderService scrapOrderService) {
        this.scrapOrderService = scrapOrderService;
    }

    /** 提交报废申请，申请人取当前登录账号 */
    @PostMapping
    public ApiResponse<ScrapOrderResponse> create(@Valid @RequestBody ScrapOrderRequest request,
                                                  @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(ScrapOrderResponse.from(
                scrapOrderService.create(request, jwt.getSubject())));
    }

    /**
     * 审批：通过 / 驳回。
     *
     * <p>通过时如果批次余量已不够，Service 会把单据自动驳回并抛 409；
     * 这样单据不会停在 PENDING，调用方也能从状态码分辨「参数错了(400)」
     * 与「数据变了(409)」。
     */
    @PostMapping("/{scrapNo}/review")
    @PreAuthorize("hasAnyRole('APPROVER','ADMIN')")
    public ApiResponse<ScrapOrderResponse> review(@Size(max = 40) @PathVariable String scrapNo,
                                                  @Valid @RequestBody ScrapReviewRequest request,
                                                  @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(ScrapOrderResponse.from(
                scrapOrderService.review(scrapNo, jwt.getSubject(), request).order()));
    }

    /** 撤销：任何登录用户可尝试，Service 校验是否本人 */
    @PostMapping("/{scrapNo}/cancel")
    public ApiResponse<ScrapOrderResponse> cancel(@Size(max = 40) @PathVariable String scrapNo,
                                                  @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(ScrapOrderResponse.from(
                scrapOrderService.cancel(scrapNo, jwt.getSubject())));
    }

    /** 详情：本人，或 APPROVER / ADMIN */
    @GetMapping("/{scrapNo}")
    public ApiResponse<ScrapOrderResponse> detail(@Size(max = 40) @PathVariable String scrapNo,
                                                  Authentication authentication) {
        ScrapOrder order = scrapOrderService.detail(scrapNo);
        if (!canReadOthers(authentication) && !order.getApplicant().equals(authentication.getName())) {
            throw new ForbiddenException("只能查看自己的报废单");
        }
        return ApiResponse.ok(ScrapOrderResponse.from(order));
    }

    /**
     * 分页。不传 applicant 时：普通用户默认查自己；APPROVER / ADMIN 默认查全部。
     * 普通用户显式传别人的用户名会被拒绝，与领用申请列表同一套规则。
     */
    @GetMapping
    public ApiResponse<PageResponse<ScrapOrderResponse>> page(
            @Size(max = 100) @RequestParam(required = false) String applicant,
            @RequestParam(required = false) ScrapOrderStatus status,
            @Min(0) @RequestParam(defaultValue = "0") int page,
            @Min(1) @Max(100) @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            Authentication authentication) {
        boolean canReadOthers = canReadOthers(authentication);
        String target;
        if (applicant == null || applicant.isBlank()) {
            target = canReadOthers ? null : authentication.getName();
        } else {
            if (!canReadOthers && !applicant.equals(authentication.getName())) {
                throw new ForbiddenException("只能查看自己的报废单");
            }
            target = applicant;
        }
        Sort order = PageQuery.parse(sort, SCRAP_SORTABLE, Sort.by(Sort.Direction.DESC, "createdAt"));
        Pageable pageable = PageQuery.of(page, size, order);
        return ApiResponse.ok(PageResponse.from(
                scrapOrderService.page(target, status, pageable), ScrapOrderResponse::from));
    }

    private static boolean canReadOthers(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_APPROVER"));
    }
}
