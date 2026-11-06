package com.lazybone.hazmat.msds.vector;

import com.lazybone.hazmat.entity.MsdsChunk;
import com.lazybone.hazmat.msds.MsdsVectorIndex;
import com.lazybone.hazmat.msds.SearchFilter;
import com.lazybone.hazmat.msds.ai.EmbeddingClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认实现：内存里的向量快照，查询时暴力余弦。
 *
 * <p>直接复用 {@link MsdsVectorIndex}，保证开启 pgvector 之前的行为与之前逐字节一致；
 * 这一层只是把「快照从哪来、结果怎么表达」与业务代码隔开。
 *
 * <p>不实现 {@code upsert} / {@code deleteByVersions}：内存快照每次都从数据库全量重建，
 * 增量写没有意义。需要版本操作时切到 pgvector 实现。
 */
public class InMemoryVectorStore implements VectorStore {

    /** 与 {@link MsdsVectorIndex} 的内部候选深度保持一致 */
    private static final int MAX_DEPTH = 50;

    private volatile MsdsVectorIndex index = MsdsVectorIndex.empty();

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public void refresh(List<MsdsChunk> chunks, EmbeddingClient client) {
        index = MsdsVectorIndex.build(chunks, client);
    }

    @Override
    public List<ScoredChunk> search(float[] query, VectorFilter filter, int limit, double minScore) {
        VectorFilter effective = filter == null ? VectorFilter.NONE : filter;
        // indexVersion 为空时不过滤；B4 切换后内存实现按块上的版本号再筛一遍
        List<MsdsVectorIndex.Hit> hits = index.searchFiltered(
                query, SearchFilter.of(effective.casNo(), effective.docType()), minScore);
        List<ScoredChunk> scored = new ArrayList<>(hits.size());
        for (MsdsVectorIndex.Hit hit : hits) {
            if (effective.indexVersion() != null
                    && !effective.indexVersion().equals(hit.chunk().getIndexVersion())) {
                continue;
            }
            scored.add(new ScoredChunk(hit.chunk().getId(), hit.score()));
        }
        int max = Math.max(1, Math.min(limit, MAX_DEPTH));
        return scored.size() <= max ? scored : scored.subList(0, max);
    }

    @Override
    public java.util.Set<Long> existingIds(java.util.Collection<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty() || index.isEmpty()) {
            return java.util.Set.of();
        }
        java.util.Set<Long> known = index.chunkIds();
        java.util.Set<Long> found = new java.util.HashSet<>();
        for (Long id : chunkIds) {
            if (id != null && known.contains(id)) {
                found.add(id);
            }
        }
        return found;
    }

    @Override
    public long count(String indexVersion) {
        // 内存索引每次只从 active 版本重建，里面本来就只有当前版本；
        // 传入版本号时不必再筛，否则会永远返回 0
        return index.size();
    }

    @Override
    public boolean available() {
        return !index.isEmpty();
    }
}
