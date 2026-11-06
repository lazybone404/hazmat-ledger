package com.lazybone.hazmat.msds.vector;

/**
 * 向量检索的一条命中：块 id 与相似度。
 *
 * <p>只回 id 不回实体，是为了让 pgvector 实现保持纯 SQL、不必依赖 JPA；
 * 正文由检索服务按 id 回表，拿到的一定是库里的最新内容，而不是向量库里的副本。
 */
public record ScoredChunk(long chunkId, double score) {
}
