package com.lazybone.hazmat.msds;

import com.lazybone.hazmat.entity.MsdsChunk;
import com.lazybone.hazmat.msds.ai.EmbeddingClient;
import com.lazybone.hazmat.msds.ai.VectorCodec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 向量路：内存里的块向量快照，查询时暴力余弦。
 *
 * 几百块 × 512 维，遍历一次是毫秒级，不需要 ANN。只有到几十万块才值得引入近似索引，
 * 本项目离那个量级差三个数量级。
 *
 * 只收「模型名与维度都和当前客户端一致」的向量：旧模型留下的向量算得出数，
 * 但不在同一个空间，混进来会得到看似正常、实际毫无意义的排序。
 */
public final class MsdsVectorIndex {

    private static final int MAX_DEPTH = 50;

    private final List<Entry> entries;

    private MsdsVectorIndex(List<Entry> entries) {
        this.entries = entries;
    }

    public record Entry(MsdsChunk chunk, float[] vector) {
    }

    public record Hit(MsdsChunk chunk, double score) {
    }

    public static MsdsVectorIndex empty() {
        return new MsdsVectorIndex(List.of());
    }

    public static MsdsVectorIndex build(List<MsdsChunk> chunks, EmbeddingClient client) {
        List<Entry> entries = new ArrayList<>();
        for (MsdsChunk chunk : chunks) {
            VectorCodec.Decoded decoded = VectorCodec.decode(chunk.getVector());
            if (decoded == null
                    || !decoded.model().equals(client.name())
                    || decoded.vector().length != client.dimensions()) {
                continue;
            }
            entries.add(new Entry(chunk, decoded.vector()));
        }
        return new MsdsVectorIndex(List.copyOf(entries));
    }

    /**
     * @param minScore 余弦下限。为 0 时仍会丢掉 0 分与负分——它们和查询没有正相关，
     *                 放进融合只会占住名次
     */
    public List<Hit> search(float[] query, String casNo, double minScore) {
        return searchFiltered(query, SearchFilter.byCasNo(casNo), minScore);
    }

    /** 带完整过滤条件的检索；名字与 {@link MsdsLiteralIndex#searchFiltered} 保持一致，避免 null 实参歧义 */
    public List<Hit> searchFiltered(float[] query, SearchFilter filter, double minScore) {
        if (query == null || query.length == 0 || entries.isEmpty()) {
            return List.of();
        }
        SearchFilter effective = filter == null ? SearchFilter.NONE : filter;
        List<Hit> hits = new ArrayList<>();
        for (Entry entry : entries) {
            if (!effective.matches(entry.chunk())) {
                continue;
            }
            double score = VectorCodec.cosine(query, entry.vector());
            if (score <= 0 || score < minScore) {
                continue;
            }
            hits.add(new Hit(entry.chunk(), score));
        }
        hits.sort(Comparator
                .comparingDouble(Hit::score).reversed()
                .thenComparing(hit -> hit.chunk().getCasNo())
                .thenComparingInt(hit -> hit.chunk().getSectionNo())
                .thenComparingInt(hit -> hit.chunk().getPartNo()));
        return hits.size() <= MAX_DEPTH ? hits : hits.subList(0, MAX_DEPTH);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
