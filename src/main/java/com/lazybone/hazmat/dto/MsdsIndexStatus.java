package com.lazybone.hazmat.dto;

import java.time.LocalDateTime;

/**
 * 向量索引状态。字段分成三组：
 *
 * <ul>
 *   <li>进度：块总数、已编码、待编码、已放弃；</li>
 *   <li>故障：是否暂停、原因、时间；</li>
 *   <li>身份：当前模型与维度、切块/表结构版本、元数据是否一致、是否需重建。</li>
 * </ul>
 *
 * {@code encoded + pending + failed} 等于 {@code chunks}：三态互斥，不重叠。
 * {@code failed} 是「失败次数已达上限、不再自动重试」的块，普通失败仍算在
 * {@code pending} 里，下一轮会再试。
 */
public record MsdsIndexStatus(
        int chunks,
        int encoded,
        int pending,
        int failed,
        boolean paused,
        String pausedReason,
        LocalDateTime pausedAt,
        String model,
        int dimensions,
        int chunkVersion,
        int schemaVersion,
        boolean metadataReady,
        boolean needsRebuild,
        String rebuildReason,
        boolean encodingInProgress) {
}
