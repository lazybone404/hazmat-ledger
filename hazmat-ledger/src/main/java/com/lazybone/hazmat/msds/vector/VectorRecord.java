package com.lazybone.hazmat.msds.vector;

/**
 * 一条待写入向量存储的记录。
 *
 * <p>只带存储层需要的字段，不带 JPA 实体：pgvector 实现不需要知道 {@link com.lazybone.hazmat.entity.MsdsChunk}
 * 的其余列，回表由检索服务统一做。
 *
 * <p>{@code indexVersion} 现在允许为空（B4 版本化切换前所有块都属于「当前版本」），
 * 存储层必须容忍空值，不能把它当成过滤条件去比较。
 */
public record VectorRecord(
        long chunkId,
        String casNo,
        String docType,
        String indexVersion,
        String modelId,
        float[] vector
) {
}
