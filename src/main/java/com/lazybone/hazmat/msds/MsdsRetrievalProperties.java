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
 * 权重按「向量路是语义模型还是本地哈希」分两档，不写死一个比例：
 *
 * <ul>
 *   <li>本地哈希（无 key 兜底）：3:1。20 道可答题上字面 17、向量 6，
 *       等权只有 14，3:1 升到 18。3:1 是「字面为主、向量补召回」。</li>
 *   <li>语义模型（bge-m3 等）：1:2。E4 实测纯向量 Hit@5 20/20，
 *       沿用 3:1 反而压到 18/20；1:2 是 20/20、MRR 0.867 的最优档。</li>
 * </ul>
 *
 * 换个更强的模型却沿用旧权重，会出现「模型更好、融合结果更差」。
 * 两档都能在 application.properties 覆盖；改这里不需要重新编码，只影响排序。
 */
@Data
@Component
@ConfigurationProperties(prefix = "hazmat.msds.retrieval")
public class MsdsRetrievalProperties {

    /** 每一路进入融合的候选数，上限 50（与索引侧深度一致） */
    private int depth = 50;

    /** RRF 的平滑常数。10 比常见的 60 更看重头部名次，适合几百块的规模 */
    private int rrfK = 10;

    /** 本地哈希兜底时的字面权重 */
    private double literalWeight = 3.0;

    /** 本地哈希兜底时的向量权重 */
    private double vectorWeight = 1.0;

    /** 语义模型下的字面权重，默认来自 E4 的 1:2 */
    private double semanticLiteralWeight = 1.0;

    /** 语义模型下的向量权重，默认来自 E4 的 1:2 */
    private double semanticVectorWeight = 2.0;

    public int effectiveDepth() {
        return Math.max(1, Math.min(depth, 50));
    }

    public double literalWeightFor(boolean semantic) {
        return semantic ? semanticLiteralWeight : literalWeight;
    }

    public double vectorWeightFor(boolean semantic) {
        return semantic ? semanticVectorWeight : vectorWeight;
    }
}
