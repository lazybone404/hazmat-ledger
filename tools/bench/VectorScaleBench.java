import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * 暴力向量检索的规模临界点微基准。
 *
 * 回答一个问题：内存里线性扫全量余弦，到底能撑到多少块、多少延迟。
 * 两条路径都测：
 *   current    —— 照抄 {@code MsdsVectorIndex.search} + {@code VectorCodec.cosine}：
 *                 每行重算 query 的模长、每条命中分配 Hit 对象、最后全量排序取前 50。
 *   optimized  —— 预归一化后只算点积，用 50 元素小顶堆取 topK，不分配中间对象。
 * 两者之差就是「不改架构、只改热路径」能拿到的收益；绝对值决定什么时候必须换 ANN。
 *
 * 用法（JDK 21+ 单文件源码模式，不需要编译）：
 *   java -Xmx6g tools/bench/VectorScaleBench.java
 *   java -Xmx6g tools/bench/VectorScaleBench.java 1000,10000,50000,100000
 *
 * 输出是 Markdown 表格，可直接贴进 docs/实验记录.md。
 * 数字强依赖机器（本机：RTX 3060 Laptop / 16GB RAM 的开发机），换机器要重跑。
 */
public class VectorScaleBench {

    /** 与 bge-m3 一致 */
    private static final int DIMENSION = 1024;

    /** 与 MsdsVectorIndex.MAX_DEPTH 一致 */
    private static final int TOP_K = 50;

    /** 防止 JIT 把整段计算当死代码消掉 */
    private static volatile double sink;

    public static void main(String[] args) {
        int[] sizes = args.length > 0
                ? Arrays.stream(args[0].split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray()
                : new int[]{1_000, 10_000, 50_000, 100_000, 200_000};

        System.out.println("机器: " + System.getProperty("os.name") + " / "
                + Runtime.getRuntime().availableProcessors() + " 核 / maxHeap="
                + Runtime.getRuntime().maxMemory() / (1024 * 1024) + "MB");
        System.out.println("维度=" + DIMENSION + " topK=" + TOP_K + " JDK=" + System.getProperty("java.version"));
        System.out.println();
        System.out.println("| 块数 | 向量内存 | current 平均 | current P95 | optimized 平均 | optimized P95 | 加速比 |");
        System.out.println("|---:|---:|---:|---:|---:|---:|---:|");

        for (int n : sizes) {
            runSize(n);
        }
        // 读一次校验和，保证前面的计算不会被 JIT 当成死代码消掉
        System.out.printf("%nchecksum=%.3f%n", sink);
    }

    private static void runSize(int n) {
        Random random = new Random(42);
        float[][] vectors = new float[n][DIMENSION];
        for (int i = 0; i < n; i++) {
            for (int d = 0; d < DIMENSION; d++) {
                vectors[i][d] = (float) random.nextGaussian();
            }
        }
        float[] query = randomVector(random);
        float[] normalizedQuery = normalize(query.clone());

        float[][] normalized = new float[n][];
        for (int i = 0; i < n; i++) {
            normalized[i] = normalize(vectors[i].clone());
        }

        long vectorBytes = (long) n * DIMENSION * Float.BYTES;

        // 迭代次数按「总行数预算」定：约 200 万行 × 1024 维，整体几十秒跑完。
        // 小块多跑几次压噪声，大块少跑几次控制总时长。
        int iterations = Math.max(30, Math.min(500, 2_000_000 / n));
        int warmup = Math.max(3, iterations / 10);

        for (int i = 0; i < warmup; i++) {
            sink += current(vectors, query);
            sink += optimized(normalized, normalizedQuery);
        }

        long[] currentNanos = new long[iterations];
        long[] optimizedNanos = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            sink += current(vectors, query);
            currentNanos[i] = System.nanoTime() - start;

            start = System.nanoTime();
            sink += optimized(normalized, normalizedQuery);
            optimizedNanos[i] = System.nanoTime() - start;
        }

        Arrays.sort(currentNanos);
        Arrays.sort(optimizedNanos);
        double currentAvg = average(currentNanos) / 1_000_000.0;
        double currentP95 = percentile(currentNanos, 95) / 1_000_000.0;
        double optimizedAvg = average(optimizedNanos) / 1_000_000.0;
        double optimizedP95 = percentile(optimizedNanos, 95) / 1_000_000.0;

        System.out.printf("| %,d | %s | %.2f ms | %.2f ms | %.2f ms | %.2f ms | %.1fx |%n",
                n, humanBytes(vectorBytes), currentAvg, currentP95, optimizedAvg, optimizedP95,
                currentAvg / Math.max(0.0001, optimizedAvg));

    }

    /**
     * 与生产代码同构：每个向量算完整余弦（含 query 模长的重复计算），
     * 命中就 new 一个 Hit，最后全排序。
     */
    private static double current(float[][] vectors, float[] query) {
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < vectors.length; i++) {
            double score = cosine(query, vectors[i]);
            if (score > 0) {
                hits.add(new Hit(i, score));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed()
                .thenComparingInt(Hit::index));
        int limit = Math.min(TOP_K, hits.size());
        double sum = 0;
        for (int i = 0; i < limit; i++) {
            sum += hits.get(i).score;
        }
        return sum;
    }

    /** 预归一化 + 点积 + 固定大小小顶堆，只保留 topK */
    private static double optimized(float[][] normalizedVectors, float[] normalizedQuery) {
        PriorityQueue<Double> top = new PriorityQueue<>(TOP_K);
        for (float[] vector : normalizedVectors) {
            double score = dot(normalizedQuery, vector);
            if (score <= 0) {
                continue;
            }
            if (top.size() < TOP_K) {
                top.offer(score);
            } else if (score > top.peek()) {
                top.poll();
                top.offer(score);
            }
        }
        double sum = 0;
        while (!top.isEmpty()) {
            sum += top.poll();
        }
        return sum;
    }

    /** 照抄 VectorCodec.cosine：故意的，它每行都重算 query 的模长 */
    private static double cosine(float[] a, float[] b) {
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

    private static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    private static float[] randomVector(Random random) {
        float[] vector = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            vector[i] = (float) random.nextGaussian();
        }
        return vector;
    }

    private static float[] normalize(float[] vector) {
        double norm = 0;
        for (float value : vector) {
            norm += (double) value * value;
        }
        float scale = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= scale;
        }
        return vector;
    }

    private static double average(long[] values) {
        long sum = 0;
        for (long value : values) {
            sum += value;
        }
        return (double) sum / values.length;
    }

    private static long percentile(long[] sorted, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static String humanBytes(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private record Hit(int index, double score) {
    }
}
