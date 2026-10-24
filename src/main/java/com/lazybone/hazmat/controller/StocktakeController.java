package com.lazybone.hazmat.controller;

import com.lazybone.hazmat.common.ApiResponse;
import com.lazybone.hazmat.common.PageQuery;
import com.lazybone.hazmat.common.PageResponse;
import com.lazybone.hazmat.dto.StocktakeCreateRequest;
import com.lazybone.hazmat.dto.StocktakeItemRequest;
import com.lazybone.hazmat.dto.StocktakeResponse;
import com.lazybone.hazmat.dto.StocktakeSummary;
import com.lazybone.hazmat.entity.Stocktake;
import com.lazybone.hazmat.entity.StocktakeStatus;
import com.lazybone.hazmat.service.StocktakeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 库存盘点的流程接口。
 *
 * 和领用申请一样，每个动作一个子资源（submit / confirm / cancel），
 * 不用「PUT 改状态」的通用接口——四个动作的前置状态、权限、附带字段都不一样。
 *
 * <b>为什么全流程限 ADMIN。</b>盘点会直接改台账余额，是这个系统里权限最高的一类写操作，
 * 比入库、报废更敏感（那两种至少还有「单号 + 原因」作为约束）。普通用户的读权限保留：
 * 知道库里到底有多少，对同实验室所有人都是有用信息。
 *
 * <b>提交人与确认人是否必须分开？</b>这里没有强制，只分别留痕。
 * 原因是单管理员部署（很多小型实验室就一个库管）时强制会把流程卡死；
 * 真要分离，得先有「多人具备 ADMIN」这个前提，属于部署策略而不是代码规则。
 * 留痕能保证事后审计回答得出「谁数的、谁认的」。
 */
@RestController
@RequestMapping("/api/stocktakes")
@Validated
public class StocktakeController {

    /** 可排序字段白名单，理由见 {@link PageQuery#parse} */
    private static final Set<String> STOCKTAKE_SORTABLE = Set.of("id", "createdAt", "status");

    private final StocktakeService stocktakeService;

    public StocktakeController(StocktakeService stocktakeService) {
        this.stocktakeService = stocktakeService;
    }

    /**
     * 建单：不传 chemicalIds 就是「全部有库存的批次」。
     *
     * 不传时默认全盘，是因为最常见的场景是月度全盘；按品类分次盘是补充用法。
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<StocktakeResponse> create(@Valid @RequestBody StocktakeCreateRequest request,
                                                 @AuthenticationPrincipal Jwt jwt) {
        Stocktake stocktake = stocktakeService.create(request, jwt.getSubject());
        return ApiResponse.ok(stocktakeService.view(stocktake.getStocktakeNo()));
    }

    /** 盘点单列表：可按状态筛选，默认按创建时间倒序 */
    @GetMapping
    public ApiResponse<PageResponse<StocktakeSummary>> list(
            @RequestParam(required = false) StocktakeStatus status,
            @Min(0) @RequestParam(defaultValue = "0") int page,
            @Min(1) @Max(100) @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        Sort order = PageQuery.parse(sort, STOCKTAKE_SORTABLE,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Pageable pageable = PageQuery.of(page, size, order);
        return ApiResponse.ok(PageResponse.from(
                stocktakeService.page(status, pageable), StocktakeSummary::from));
    }

    /** 盘点单详情：单据 + 全部明细（含账面量、实盘量、差异） */
    @GetMapping("/{stocktakeNo}")
    public ApiResponse<StocktakeResponse> detail(@Size(max = 40) @PathVariable String stocktakeNo) {
        return ApiResponse.ok(stocktakeService.view(stocktakeNo));
    }


    /**
     * 录入一条实盘量。
     *
     * 用 PUT 而不是 POST：同一条明细反复录入是「改写这个格子」，语义上幂等——
     * 数错了重数一遍覆盖即可，不应该产生第二条记录。
     */
    @PutMapping("/{stocktakeNo}/items/{itemId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<StocktakeResponse> recordActual(
            @Size(max = 40) @PathVariable String stocktakeNo,
            @Positive @PathVariable Long itemId,
            @Valid @RequestBody StocktakeItemRequest request) {
        stocktakeService.recordActual(stocktakeNo, itemId, request);
        return ApiResponse.ok(stocktakeService.view(stocktakeNo));
    }

    /** 提交：要求全部批次都录过实盘量，否则列出漏录的批次号 */
    @PostMapping("/{stocktakeNo}/submit")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<StocktakeResponse> submit(@Size(max = 40) @PathVariable String stocktakeNo,
                                                 @AuthenticationPrincipal Jwt jwt) {
        stocktakeService.submit(stocktakeNo, jwt.getSubject());
        return ApiResponse.ok(stocktakeService.view(stocktakeNo));
    }

    /**
     * 确认：把差异写成 ADJUST 流水并调整批次余额。
     *
     * 这是整条链路里唯一改余额的动作，返回调账后的详情，前端能立刻看到
     * 「差异已入账」而不是只拿到一个 200。
     */
    @PostMapping("/{stocktakeNo}/confirm")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<StocktakeResponse> confirm(@Size(max = 40) @PathVariable String stocktakeNo,
                                                  @AuthenticationPrincipal Jwt jwt) {
        stocktakeService.confirm(stocktakeNo, jwt.getSubject());
        return ApiResponse.ok(stocktakeService.view(stocktakeNo));
    }

    /** 撤销：不调账。草稿和已提交都能撤，已确认的不能 */
    @PostMapping("/{stocktakeNo}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<StocktakeResponse> cancel(@Size(max = 40) @PathVariable String stocktakeNo,
                                                 @AuthenticationPrincipal Jwt jwt) {
        stocktakeService.cancel(stocktakeNo, jwt.getSubject());
        return ApiResponse.ok(stocktakeService.view(stocktakeNo));
    }
}
