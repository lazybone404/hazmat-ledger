package com.lazybone.hazmat.controller;

import com.lazybone.hazmat.common.ApiResponse;
import com.lazybone.hazmat.dto.MsdsAnswer;
import com.lazybone.hazmat.dto.MsdsIndexStatus;
import com.lazybone.hazmat.dto.MsdsReindexResult;
import com.lazybone.hazmat.dto.MsdsSearchResult;
import com.lazybone.hazmat.msds.MsdsAnswerService;
import com.lazybone.hazmat.msds.MsdsIndexService;
import com.lazybone.hazmat.msds.MsdsSearchService;
import com.lazybone.hazmat.msds.MsdsVectorService;
import com.lazybone.hazmat.msds.SearchFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MSDS 安全问答：检索原文、生成带引用的回答、手动重建索引。
 *
 * 生成不可用时 {@code /ask} 仍返回检索片段（{@code generated=false}），
 * 不会把「没配模型」变成接口错误。
 */
@RestController
@RequestMapping("/api/msds")
public class MsdsController {

    private final MsdsSearchService searchService;
    private final MsdsAnswerService answerService;
    private final MsdsIndexService indexService;
    private final MsdsVectorService vectorService;

    public MsdsController(MsdsSearchService searchService,
                          MsdsAnswerService answerService,
                          MsdsIndexService indexService,
                          MsdsVectorService vectorService) {
        this.searchService = searchService;
        this.answerService = answerService;
        this.indexService = indexService;
        this.vectorService = vectorService;
    }

    /**
     * 检索 MSDS 原文：字面路与向量路融合，降级信息随结果返回。
     *
     * @param q       查询内容，如「甲醇误食怎么办」
     * @param casNo   可选，限定某一种试剂
     * @param docType 可选，限定知识源类型（MSDS / GB / SOP）；不传则三类一起检索
     * @param topK    返回条数，默认 5，最多 20
     */
    @GetMapping("/search")
    public ApiResponse<MsdsSearchResult> search(@RequestParam("q") String q,
                                                @RequestParam(value = "casNo", required = false) String casNo,
                                                @RequestParam(value = "docType", required = false) String docType,
                                                @RequestParam(value = "topK", defaultValue = "5") int topK) {
        return ApiResponse.ok(searchService.searchFiltered(q, SearchFilter.of(casNo, docType), topK));
    }

    /**
     * 检索并作答：每条结论带 {@code [n]} 引用，引用列表由后端从检索结果生成。
     *
     * 没有配置生成模型或模型调用失败时，退回抽取式原文片段（{@code generated=false}）；
     * 检索为空或降级到无法确认时返回 {@code answerable=false}，不编造答案。
     *
     * @param q     问题，如「甲醇误食怎么办」
     * @param casNo 可选，限定某一种试剂
     * @param topK  参与作答的检索条数，默认 5，最多 20
     */
    @GetMapping("/ask")
    public ApiResponse<MsdsAnswer> ask(@RequestParam("q") String q,
                                       @RequestParam(value = "casNo", required = false) String casNo,
                                       @RequestParam(value = "topK", defaultValue = "5") int topK) {
        return ApiResponse.ok(answerService.ask(q, casNo, topK));
    }

    /**
     * 重建索引：先扫文件增量落块，再按四元组决定是否作废旧向量，最后在后台开始编码。
     *
     * 编码不同步执行——有 key 时要调几十次模型，接口不能挂在那里等。
     * 进度和失败原因查 {@code /api/msds/status}。
     *
     * <b>限 ADMIN。</b>重建会清失败暂停并触发模型调用，是花钱且影响全局索引的操作，
     * 不能对普通账号开放。
     */
    @PostMapping("/reindex")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<MsdsReindexResult> reindex() {
        MsdsIndexService.RebuildResult rebuild = indexService.rebuild();
        MsdsVectorService.ResetResult vectors = vectorService.prepareForReindex();
        vectorService.scheduleEncoding();
        return ApiResponse.ok(new MsdsReindexResult(rebuild, vectors));
    }

    /** 向量索引状态：进度、暂停原因、模型与四元组是否一致。限 ADMIN。 */
    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<MsdsIndexStatus> status() {
        return ApiResponse.ok(vectorService.status());
    }
}
