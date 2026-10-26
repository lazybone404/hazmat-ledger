package com.lazybone.hazmat.msds;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 融合检索的参数。
 *
 * 两路候选深度默认 50：接口最终只返回 5–20 条，但融合需要看到更深的候选，
 * 否则「字面路排 30、向量路排 3」的正确块在截断时就丢了。
 *
 * 权重默认 3:1，不是等权。依据是当前唯一可复现的评测环境（无 key、本地哈希）：
 * 20 道可答题上字面路 17、向量路 6，等权融合反而降到 14，3:1 后升到 18。
 * 云端模型接入后必须用同一套题集重标，不能把这个比例直接带过去——两路的
 * 精度差变了，最优权重也会变。改这里不需要重新编码，只影响排序。
 */
@Data
@Component
@ConfigurationProperties(prefix = "hazmat.msds.retrieval")
public class MsdsRetrievalProperties {

    /** 每一路进入融合的候选数，上限 50（与索引侧深度一致） */
    private int depth = 50;

    /** RRF 的平滑常数。10 比常见的 60 更看重头部名次，适合几百块的规模 */
    private int rrfK = 10;

    private double literalWeight = 3.0;

    private double vectorWeight = 1.0;

    public int effectiveDepth() {
        return Math.max(1, Math.min(depth, 50));
    }
}
