package com.lazybone.hazmat.controller;

import com.lazybone.hazmat.common.ApiResponse;
import com.lazybone.hazmat.common.ForbiddenException;
import com.lazybone.hazmat.common.PageQuery;
import com.lazybone.hazmat.common.PageResponse;
import com.lazybone.hazmat.dto.ChemicalSafetyOverview;
import com.lazybone.hazmat.dto.RequisitionRequest;
import com.lazybone.hazmat.dto.RequisitionResponse;
import com.lazybone.hazmat.dto.ReviewRequest;
import com.lazybone.hazmat.entity.RequisitionLog;
import com.lazybone.hazmat.entity.RequisitionStatus;
import com.lazybone.hazmat.service.ChemicalSafetyService;
import com.lazybone.hazmat.service.RequisitionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 领用申请的流程接口。
 *
 * 四个动作（审批 / 出库 / 核销 / 撤回）都做成独立的 POST 子资源，而不是一个
 * 「PUT /requisitions/{id} 改状态」的通用接口。原因是这四个动作的**权限、前置状态、
 * 附带字段各不相同**：审批要审批人角色，出库要库管（本项目中归 ADMIN），撤回只能本人。
 * 合成一个接口的话，这些差异全得塞进 if，而且前端可以构造出任意状态跳转。
 *
 * <b>身份一律来自 JWT，不从请求参数读。</b>申请人、审批人、操作人如果允许客户端自报，
 * 「撤回只能本人」「双人复核必须是两个人」这类规则都可以被一个改参数的动作绕过。
 */
@RestController
@RequestMapping("/api/requisitions")
@Validated
public class RequisitionController {

    /** 可排序字段白名单，理由见 {@link PageQuery#parse} */
    private static final Set<String> REQUISITION_SORTABLE =
            Set.of("id", "createdAt", "status", "quantity");

    private final RequisitionService requisitionService;
    private final ChemicalSafetyService chemicalSafetyService;

    public RequisitionController(RequisitionService requisitionService,
                                 ChemicalSafetyService chemicalSafetyService) {
        this.requisitionService = requisitionService;
        this.chemicalSafetyService = chemicalSafetyService;
    }

    /** 提交申请，申请人取当前登录账号 */
    @PostMapping
    public ApiResponse<RequisitionResponse> submit(@Valid @RequestBody RequisitionRequest request,
                                                   @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(RequisitionResponse.from(
                requisitionService.submit(jwt.getSubject(), request)));
    }

    /** 审批：通过 / 驳回。普通用户没有审批权，即使知道单号也调不动 */
    @PostMapping("/{reqNo}/review")
    @PreAuthorize("hasAnyRole('APPROVER','ADMIN')")
    public ApiResponse<RequisitionResponse> review(@Size(max = 40) @PathVariable String reqNo,
                                                   @Valid @RequestBody ReviewRequest request,
                                                   @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(RequisitionResponse.from(
                requisitionService.review(reqNo, jwt.getSubject(), request)));
    }

    /** 出库 */
    @PostMapping("/{reqNo}/issue")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<RequisitionResponse> issue(@Size(max = 40) @PathVariable String reqNo,
                                                  @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(RequisitionResponse.from(
                requisitionService.issue(reqNo, jwt.getSubject())));
    }

    /** 核销 */
    @PostMapping("/{reqNo}/close")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<RequisitionResponse> close(@Size(max = 40) @PathVariable String reqNo,
                                                  @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(RequisitionResponse.from(
                requisitionService.close(reqNo, jwt.getSubject())));
    }

    /**
     * 撤回（只允许申请人本人）。
     *
     * 这里不加角色限制：任何登录用户都可以尝试撤回，但 Service 会比对本人的身份。
     * 把「本人」判断放 Service，是因为它换入口（定时任务、批处理）也照样要守；
     * 角色限制则只对 HTTP 入口有意义，放 Controller。
     */
    @PostMapping("/{reqNo}/cancel")
    public ApiResponse<RequisitionResponse> cancel(@Size(max = 40) @PathVariable String reqNo,
                                                    @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(RequisitionResponse.from(
                requisitionService.cancel(reqNo, jwt.getSubject())));
    }

    /**
     * 申请详情：申请本身 + 完整流转时间线。
     * 返回一个组合对象而不是两次请求，是因为时间线离开申请没有意义，
     * 分两次拿还可能出现「拿到申请、时间线已经被追加了两条」的错位。
     *
     * 查看权限：本人，或 APPROVER / ADMIN。普通用户看别人的申请会泄露用途与用量。
     */
    @GetMapping("/{reqNo}")
    public ApiResponse<DetailView> detail(@Size(max = 40) @PathVariable String reqNo, Authentication authentication) {
        RequisitionService.Detail d = requisitionService.detail(reqNo);
        if (!canReadOthers(authentication)
                && !d.requisition().getApplicant().equals(authentication.getName())) {
            throw new ForbiddenException("只能查看自己的申请");
        }
        return ApiResponse.ok(new DetailView(RequisitionResponse.from(d.requisition()),
                d.timeline().stream().map(LogView::from).toList()));
    }

    /**
     * 领用详情页的安全摘要：固定取 MSDS 的 2/4/6/8 节（危害、急救、泄漏、防护）。
     *
     * <p>权限与详情一致：本人或 APPROVER / ADMIN。安全摘要本身不是秘密，
     * 但它挂在申请下面，能看申请的人才能看；这样权限模型只有一套，不用另开一条规则。
     */
    @GetMapping("/{reqNo}/safety")
    public ApiResponse<ChemicalSafetyOverview> safety(@Size(max = 40) @PathVariable String reqNo,
                                                      Authentication authentication) {
        RequisitionService.Detail d = requisitionService.detail(reqNo);
        if (!canReadOthers(authentication)
                && !d.requisition().getApplicant().equals(authentication.getName())) {
            throw new ForbiddenException("只能查看自己申请的安全信息");
        }
        return ApiResponse.ok(chemicalSafetyService.overview(d.requisition().getChemicalId()));
    }

    /**
     * 按申请人分页查询。不传 applicant 时默认查自己；查别人需要 APPROVER / ADMIN。
     * 这样普通用户不需要记住自己的用户名，也无法通过改参数看别人的记录。
     *
     * 排序白名单里没有 applicant / purpose 这类文本字段：按它们排既没有业务意义，
     * 又会把用户输入带进 order by。
     */
    @GetMapping
    public ApiResponse<PageResponse<RequisitionResponse>> byApplicant(
            @Size(max = 100) @RequestParam(required = false) String applicant,
            @RequestParam(required = false) RequisitionStatus status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Min(0) @RequestParam(defaultValue = "0") int page,
            @Min(1) @Max(100) @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            Authentication authentication) {
        String target = (applicant == null || applicant.isBlank())
                ? authentication.getName()
                : applicant;
        if (!target.equals(authentication.getName()) && !canReadOthers(authentication)) {
            throw new ForbiddenException("只能查看自己的申请");
        }
        Sort order = PageQuery.parse(sort, REQUISITION_SORTABLE,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Pageable pageable = PageQuery.of(page, size, order);
        return ApiResponse.ok(PageResponse.from(
                requisitionService.page(target, status, from, to, pageable),
                RequisitionResponse::from));
    }

    private static boolean canReadOthers(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_APPROVER"));
    }

    public record DetailView(RequisitionResponse requisition, List<LogView> timeline) {
    }

    public record LogView(String fromStatus, String toStatus, String operator,
                          String comment, String at) {

        static LogView from(RequisitionLog l) {
            return new LogView(
                    l.getFromStatus() == null ? null : l.getFromStatus().name(),
                    l.getToStatus().name(),
                    l.getOperator(),
                    l.getComment(),
                    l.getCreatedAt() == null ? null : l.getCreatedAt().toString());
        }
    }
}
