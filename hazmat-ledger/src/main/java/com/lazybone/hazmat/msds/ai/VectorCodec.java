package com.lazybone.hazmat.msds.ai;

import java.util.Locale;

/**
 * 向量在数据库里的存法：{@code 模型名|0.1234,-0.5678,...}
 *
 * 模型名是存储格式的一部分，不是装饰。不同模型（甚至同一模型的不同维度）算出的向量
 * 不在同一个空间，放一起算余弦数值上成立、排序却是错的。存了模型名，编码前才能识别
 * 出哪些块是旧模型留下的、必须重算。
 *
 * 用逗号而不是 JSON 数组省掉方括号与引号；保留 4 位小数，对排序精度足够。
 */
public final class VectorCodec {

    private static final String SEPARATOR = "|";

    /** 正常一维 512 的串约 4KB；超过这个量级说明存进去的不是本类写的，不必再解 */
    private static final int MAX_STORED_LENGTH = 1_000_000;

    private VectorCodec() {
    }

    public record Decoded(String model, float[] vector) {
    }

    public static String encode(String model, float[] vector) {
        StringBuilder sb = new StringBuilder(model.length() + vector.length * 8);
        sb.append(model).append(SEPARATOR);
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.ROOT, "%.4f", vector[i]));
        }
        return sb.toString();
    }

    /** 解不出来（空、格式坏、模型名为空、含非有限值）返回 null，调用方当作「这一块还没有可用向量」 */
    public static Decoded decode(String stored) {
        if (stored == null || stored.isBlank() || stored.length() > MAX_STORED_LENGTH) {
            return null;
        }
        int sep = stored.indexOf(SEPARATOR);
        if (sep <= 0) {
            return null;
        }
        String[] parts = stored.substring(sep + 1).split(",");
        float[] vector = new float[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                vector[i] = Float.parseFloat(parts[i]);
                // NaN/Infinity 能通过 parseFloat，但会让余弦变成 NaN，
                // 而 Double 比较里 NaN 会被当成最大分数，一条坏数据就能污染整个排序
                if (!Float.isFinite(vector[i])) {
                    return null;
                }
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return new Decoded(stored.substring(0, sep), vector);
    }

    /** 余弦相似度。两边归一化时等价于点积，这里仍按通用式算，防止有未归一化的向量 */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
