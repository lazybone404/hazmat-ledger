package com.lazybone.hazmat.msds.ai;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 本地哈希向量：不联网、不要 key、结果稳定。
 *
 * 做法是 hashing trick——把每个字和相邻双字哈希进固定维度的桶，带符号累加后归一化。
 * 它抓的是字面重合，不是语义：问「误食」能落到写着「食入」的块附近（双字有重叠），
 * 但问「这瓶东西能不能喝」帮不上忙。所以它只是兜底，语义那一档要靠模型。
 *
 * 为什么值得保留这一档：没有 key 的环境（CI、离线演示）必须能把索引建起来，
 * 否则向量字段永远是空的，阶段 3 的融合逻辑在无 key 时完全没法验证。
 */
@Component
public class HashingEmbeddingClient implements EmbeddingClient {

    /** 状态与检索返回里会如实显示这个名字，用来区分「语义模型」与「本地兜底」 */
    public static final String NAME = "local-hash";

    private final AiProperties properties;

    public HashingEmbeddingClient(AiProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            result.add(vectorize(text));
        }
        return result;
    }

    private float[] vectorize(String text) {
        float[] v = new float[dimensions()];
        if (text == null || text.isBlank()) {
            return v;
        }
        // 用 Locale.ROOT：土耳其语等 locale 下 "I".toLowerCase() 不是 "i"，
        // 会让同一段文本在不同机器上算出不同向量
        String t = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < t.length(); i++) {
            add(v, t.substring(i, i + 1));
            if (i + 2 <= t.length()) {
                add(v, t.substring(i, i + 2));
            }
        }
        normalize(v);
        return v;
    }

    private void add(float[] v, String gram) {
        int hash = gram.hashCode();
        int index = Math.floorMod(hash, v.length);
        // 哈希的另一位决定符号，避免所有桶都往正方向堆
        float sign = ((hash >>> 16) & 1) == 0 ? 1f : -1f;
        v[index] += sign;
    }

    private void normalize(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        if (sum == 0) {
            return;
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < v.length; i++) {
            v[i] /= norm;
        }
    }

    @Override
    public int dimensions() {
        return properties.getEmbeddingDimensions();
    }

    /**
     * 阶段 2 不参与过滤。本地哈希的分数分布与远程模型完全不同（相关也就 0.2~0.5），
     * 阈值必须在阶段 3 用本项目题集单独标定，不能沿用远程模型的数。
     */
    @Override
    public double minScore() {
        return 0.0;
    }

    @Override
    public String name() {
        return NAME;
    }
}
