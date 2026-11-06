package com.lazybone.hazmat.msds.vector;

import com.lazybone.hazmat.entity.MsdsChunk;
import com.lazybone.hazmat.msds.ai.EmbeddingClient;

import java.util.Collection;
import java.util.List;

/**
 * 向量存储抽象：内存暴力扫描与 pgvector 两种实现共用同一套调用。
 *
 * <p>为什么要有这一层：检索侧只关心「拿查询向量换回一批块 id 和分数」，
 * 不该在业务代码里写「如果没有 pg 就用内存」的分支。切换实现只改配置，
 * 默认 {@code memory}，外部依赖缺失时行为与现在完全一致。
 *
 * <p>{@link #refresh} 是「按数据库快照整体重建」的入口，内存实现真正重建索引；
 * pgvector 实现不回表——它的写入由编码流程显式 {@link #upsert} 完成，
 * 在这里重复写入只会让每次编码批次都多一轮全量 SQL。
 */
public interface VectorStore {

    /** 实现名，状态接口与日志用它说明当前跑的是哪一种存储 */
    String name();

    /**
     * 用数据库里的块快照刷新存储。
     *
     * <p>{@code chunks} 已经由调用方按模型/维度筛过；空列表表示索引应被清空
     * （例如四元组不符、旧向量已作废）。
     */
    void refresh(List<MsdsChunk> chunks, EmbeddingClient client);

    /** 按相似度降序返回至多 {@code limit} 条；{@code minScore} 以下的命中应被丢弃 */
    List<ScoredChunk> search(float[] query, VectorFilter filter, int limit, double minScore);

    /** 增量写入或覆盖同 id 的记录；pgvector 实现按 chunk_id 做 upsert */
    default void upsert(List<VectorRecord> records) {
        throw new UnsupportedOperationException(name() + " 不支持增量写入，请用 refresh 全量重建");
    }

    /** 删除指定索引版本的向量；B4 版本切换后清理旧版本用 */
    default void deleteByVersions(Collection<String> versions) {
        throw new UnsupportedOperationException(name() + " 不支持按版本删除");
    }

    /** 指定版本的行数；{@code version} 为空时返回全部行数 */
    long count(String indexVersion);

    /** 存储是否可用；不可用时检索应降级到字面路，而不是抛错 */
    boolean available();
}
