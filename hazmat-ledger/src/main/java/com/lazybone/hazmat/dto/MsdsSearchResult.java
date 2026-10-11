package com.lazybone.hazmat.dto;

import java.util.List;

/**
 * 一次 MSDS 检索的完整结果。
 *
 * 降级信息必须随结果一起返回，而不是只写日志：用户看到「只有三条结果」时，
 * 需要知道是「库里就这些」还是「向量路没跑起来」。静默退化会被误判成检索质量差。
 *
 * @param hits          命中的块，按 RRF 融合分降序
 * @param degraded      是否有一路降级
 * @param degradedReason 降级原因，正常时为 null
 * @param indexing      后台是否仍在编码向量
 * @param needsRebuild  元数据与当前配置不一致，旧向量已停用，需要显式重建
 */
public record MsdsSearchResult(
        List<MsdsHit> hits,
        boolean degraded,
        String degradedReason,
        boolean indexing,
        boolean needsRebuild
) {
}
