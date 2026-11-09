package com.lazybone.hazmat.controller;

import com.lazybone.hazmat.common.ApiResponse;
import com.lazybone.hazmat.common.PageQuery;
import com.lazybone.hazmat.common.PageResponse;
import com.lazybone.hazmat.dto.BatchResponse;
import com.lazybone.hazmat.dto.ChemicalIncompatibilityView;
import com.lazybone.hazmat.dto.ChemicalRequest;
import com.lazybone.hazmat.dto.ChemicalResponse;
import com.lazybone.hazmat.dto.ChemicalSafetyView;
import com.lazybone.hazmat.dto.ExpiringBatchResponse;
import com.lazybone.hazmat.dto.InboundRequest;
import com.lazybone.hazmat.dto.LedgerResponse;
import com.lazybone.hazmat.dto.ReconcileView;
import com.lazybone.hazmat.dto.ScrapRequest;
import com.lazybone.hazmat.dto.ScrapResult;
import com.lazybone.hazmat.dto.StockView;
import com.lazybone.hazmat.service.ChemicalSafetyService;
import com.lazybone.hazmat.service.ChemicalService;
import com.lazybone.hazmat.service.IncompatibilityService;
import com.lazybone.hazmat.service.StockService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 试剂档案与库存。
 *
 * 没有 PUT / DELETE：台账类系统里，档案一旦有了批次和流水就不允许改删，
 * 只能新增记录来修正。这不是没来得及做，是这类数据的正确姿势——
 * 所以接口的最小集合本身就说明了领域性质。
 *
 * <b>写操作限 ADMIN。</b>建档、入库、报废都会改变台账事实，普通领用人只能读。
 * 读接口只要求登录，因为库存与效期对实验室内所有人都是有用的信息。
 */
@RestController
@RequestMapping("/api/chemicals")
@Validated
public class ChemicalController {

    /**
     * 可排序字段白名单。属性名而不是数据库列名——最终由 JPA 翻译，
     * 对不上会在启动后的第一次查询才炸，所以只列实体里确实存在的字段。
     */
    private static final Set<String> CHEMICAL_SORTABLE =
            Set.of("id", "name", "casNo", "category", "hazardLevel", "createdAt");

    private static final Set<String> LEDGER_SORTABLE =
            Set.of("id", "createdAt", "quantity", "changeType");

    private final ChemicalService chemicalService;
    private final ChemicalSafetyService chemicalSafetyService;
    private final IncompatibilityService incompatibilityService;
    private final StockService stockService;

    public ChemicalController(ChemicalService chemicalService,
                              ChemicalSafetyService chemicalSafetyService,
                              IncompatibilityService incompatibilityService,
                              StockService stockService) {
        this.chemicalService = chemicalService;
        this.chemicalSafetyService = chemicalSafetyService;
        this.incompatibilityService = incompatibilityService;
        this.stockService = stockService;
    }

    /**
     * 试剂详情。列表里只有摘要字段，详情页需要按 id 取完整档案。
     *
     * 读接口只要求登录：库存与安全信息对实验室内所有人可见；
     * 建档与出入库等写操作才限 ADMIN。
     */
    @GetMapping("/{id}")
    public ApiResponse<ChemicalResponse> detail(@Positive @PathVariable Long id) {
        return ApiResponse.ok(ChemicalResponse.from(chemicalService.findById(id)));
    }

    /**
     * 安全信息入口：按试剂的 CAS 号限定检索 MSDS，前端不需要手输试剂名。
     *
     * {@code q} 必填，通常由前端用试剂名加动作词预填（如「甲醇 急救」「甲醇 误食」）。
     * 该试剂没有索引过 MSDS 时返回 {@code available=false} 与明确提示，
     * 而不是空列表——「没有资料」与「这次没查到」必须让用户分得清。
     */
    @GetMapping("/{id}/safety")
    public ApiResponse<ChemicalSafetyView> safety(
            @Positive @PathVariable Long id,
            @Size(max = 200) @RequestParam("q") String q,
            @RequestParam(value = "docType", required = false) String docType,
            @Min(1) @Max(20) @RequestParam(defaultValue = "5") int topK) {
        return ApiResponse.ok(chemicalSafetyService.safety(id, q, docType, topK));
    }

    /**
     * 某试剂与其它在库试剂的禁忌混放检查。
     *
     * 该试剂当前没有库存时返回 {@code inStock=false} 与说明，而不是把它
     * 和所有档案两两比对——混放是物理存放问题，不占柜子的试剂不该产生冲突提示。
     * {@code uncovered} 是「规则表没有记录」的搭档，不代表可以混放。
     */
    @GetMapping("/{id}/incompatibilities")
    public ApiResponse<ChemicalIncompatibilityView> incompatibilities(
            @Positive @PathVariable Long id) {
        return ApiResponse.ok(incompatibilityService.checkChemical(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ChemicalResponse> create(@Valid @RequestBody ChemicalRequest request) {
        return ApiResponse.ok(ChemicalResponse.from(chemicalService.create(request)));
    }

    /**
     * 试剂列表：分页 + 可选筛选。
     *
     * 关键词只搜名称与 CAS；分类与危险等级各用精确匹配的参数，
     * 便于前端做下拉筛选。排序字段必须在白名单内，其他一律 400。
     */
    @GetMapping
    public ApiResponse<PageResponse<ChemicalResponse>> list(
            @Size(max = 100) @RequestParam(required = false) String keyword,
            @Size(max = 50) @RequestParam(required = false) String category,
            @Size(max = 20) @RequestParam(required = false) String hazardLevel,
            @Min(0) @RequestParam(defaultValue = "0") int page,
            @Min(1) @Max(100) @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        Sort order = PageQuery.parse(sort, CHEMICAL_SORTABLE, Sort.by(Sort.Direction.ASC, "id"));
        Pageable pageable = PageQuery.of(page, size, order);
        return ApiResponse.ok(PageResponse.from(
                chemicalService.page(keyword, category, hazardLevel, pageable),
                ChemicalResponse::from));
    }

    /**
     * 效期预警：所有仍有库存、在指定天数内到期（含已过期）的批次。
     *
     * 跨试剂、按到期时间升序，是一条「今天该处理什么」的待办清单，
     * 不是某个试剂的库存详情（那个看 {@code /{id}/stock}）。
     */
    @GetMapping("/expiring")
    public ApiResponse<PageResponse<ExpiringBatchResponse>> expiring(
            @Min(1) @Max(365) @RequestParam(defaultValue = "30") int withinDays,
            @Min(0) @RequestParam(defaultValue = "0") int page,
            @Min(1) @Max(100) @RequestParam(defaultValue = "20") int size) {
        // 按 usableUntil 排而不是 expireDate：开封后的批次可用期可能更早，
        // 按包装有效期排会把最该先处理的排在后面。
        Pageable pageable = PageQuery.of(page, size, Sort.by(Sort.Direction.ASC, "usableUntil"));
        return ApiResponse.ok(stockService.expiring(withinDays, pageable));
    }

    /**
     * 某试剂的出入库流水，按时间倒序分页。
     *
     * 这是台账「可对账」的入口：每笔流水都带发生后的余额，
     * 顺着一张张对下来就能发现账实不符。
     */
    @GetMapping("/{id}/ledger")
    public ApiResponse<PageResponse<LedgerResponse>> ledger(
            @Positive @PathVariable Long id,
            @Min(0) @RequestParam(defaultValue = "0") int page,
            @Min(1) @Max(100) @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        Sort order = PageQuery.parse(sort, LEDGER_SORTABLE, Sort.by(Sort.Direction.DESC, "createdAt"));
        Pageable pageable = PageQuery.of(page, size, order);
        return ApiResponse.ok(PageResponse.from(stockService.ledger(id, pageable), LedgerResponse::from));
    }

    /** 入库：给某试剂新建一个批次，操作人取当前登录账号 */
    @PostMapping("/{id}/batches")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<BatchResponse> inbound(@Positive @PathVariable Long id,
                                              @Valid @RequestBody InboundRequest request,
                                              @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(stockService.inbound(id, request, jwt.getSubject()));
    }

    /**
     * 开封：记录开封时间，并按档案的「开封后可用天数」提前该批次的可用截止日。
     *
     * 路径挂在批次下面，和报废一致——开封针对的是「这一瓶」而不是整个试剂。
     * 不需要请求体：时间取服务端当前时间，操作人取当前登录账号，
     * 客户端能自报的只有「开哪一瓶」，这正好是唯一需要它决定的事。
     */
    @PostMapping("/{id}/batches/{batchId}/open")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<BatchResponse> open(@Positive @PathVariable Long id,
                                           @Positive @PathVariable Long batchId,
                                           @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(stockService.openBatch(id, batchId, jwt.getSubject()));
    }

    /**
     * 管理员应急报废：从某个批次直接扣掉不可用的量，必须写明原因。
     *
     * <p>常规报废走 {@code POST /api/scraps} 的审批单（申请 → 审批 → 扣减）。
     * 这个入口保留给应急场景：瓶子打碎、泄漏处置等需要当场销账的情况。
     * 两条路径写出的台账用 {@code refType} 区分：这里是 {@code SCRAP}、
     * {@code refId=batchId}；审批单是 {@code SCRAP_ORDER}、{@code refId=单据 id}。
     *
     * 路径把批次挂在试剂下面，语义上说明「批次属于这个试剂」；
     * Service 里还会再校验一次归属，防止调用方拼错 id 跨试剂扣减。
     */
    @PostMapping("/{id}/batches/{batchId}/scrap")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ScrapResult> scrap(@Positive @PathVariable Long id,
                                          @Positive @PathVariable Long batchId,
                                          @Valid @RequestBody ScrapRequest request,
                                          @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(stockService.scrap(id, batchId, request, jwt.getSubject()));
    }

    /** 库存视图：批次明细 + 可用总量 + 临期/过期预警 */
    @GetMapping("/{id}/stock")
    public ApiResponse<StockView> stock(@Positive @PathVariable Long id) {
        return ApiResponse.ok(stockService.stockView(id));
    }

    /**
     * 对账：批次余额 vs 流水余额，逐批次与汇总量两层。
     *
     * 把它做成查询接口而不是后台巡检，是因为「查得到」比「查过后通知」更有用：
     * 怀疑账不对的时候能马上自己跑一次，运维也不必为此搭一套监控。
     * 返回 200 而不是在不一致时报错——不一致是**数据状态**，不是请求失败，
     * 结果里 {@code consistent=false} 才是要看的信号。
     */
    @GetMapping("/{id}/reconcile")
    public ApiResponse<ReconcileView> reconcile(@Positive @PathVariable Long id) {
        return ApiResponse.ok(stockService.reconcile(id));
    }
}
