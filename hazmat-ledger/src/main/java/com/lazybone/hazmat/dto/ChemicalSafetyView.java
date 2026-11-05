package com.lazybone.hazmat.dto;

import com.lazybone.hazmat.entity.Chemical;

import java.util.List;

/**
 * 试剂详情页的安全入口：某个台账试剂 + 一次按 CAS 限定的 MSDS 检索。
 *
 * 与 {@link MsdsSearchResult} 的区别是多了「这份 MSDS 到底在不在库里」这一层：
 * 检索为空可能是「查了但没有相关章节」，也可能是「压根没索引过这份试剂」。
 * 前端需要分开显示——前者可以换个问法再试，后者只能提示补资料。
 *
 * @param available      该试剂是否已索引 MSDS 块
 * @param chemicalId     台账试剂 id
 * @param casNo          检索限定用的 CAS 号
 * @param chemicalName   台账里的名称
 * @param query          本次查询，前端回显用
 * @param hits           命中块，按 RRF 分降序；未索引时为空
 * @param degraded       检索是否有一路降级
 * @param degradedReason 降级原因，正常时为 null
 * @param indexing       后台是否仍在编码向量
 * @param needsRebuild   索引元数据与当前配置不一致，需要重建
 * @param message        面向用户的说明；正常命中时为 null
 */
public record ChemicalSafetyView(
        boolean available,
        Long chemicalId,
        String casNo,
        String chemicalName,
        String query,
        List<MsdsHit> hits,
        boolean degraded,
        String degradedReason,
        boolean indexing,
        boolean needsRebuild,
        String message
) {

    /** 该试剂没有 MSDS 块：返回明确的「没有资料」，而不是空列表让人猜 */
    public static ChemicalSafetyView unavailable(Chemical chemical, String query) {
        return new ChemicalSafetyView(
                false, chemical.getId(), chemical.getCasNo(), chemical.getName(), query,
                List.of(), false, null, false, false,
                "该试剂尚未索引 MSDS 原文，暂无法提供安全信息");
    }

    public static ChemicalSafetyView available(Chemical chemical, String query, MsdsSearchResult result) {
        String message = result.hits().isEmpty() && !result.degraded()
                ? "已索引该试剂的 MSDS，但本次查询没有匹配到相关章节"
                : null;
        return new ChemicalSafetyView(
                true, chemical.getId(), chemical.getCasNo(), chemical.getName(), query,
                result.hits(), result.degraded(), result.degradedReason(),
                result.indexing(), result.needsRebuild(), message);
    }
}
