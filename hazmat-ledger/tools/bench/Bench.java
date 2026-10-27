import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 最小 HTTP 压测器：固定并发、固定时长，输出 QPS 与延迟分位。
 *
 * 用法：
 *   java Bench.java <名称> <GET|POST> <url> <并发> <秒数> <token> [body]
 *
 * body 里的 ${seq} 会被替换成全局自增序号，用来给写入场景造唯一批次号。
 * 只依赖 JDK 21 与虚拟线程，不需要额外压测工具。
 */
public class Bench {

    public static void main(String[] args) throws Exception {
        String name = args[0];
        String method = args[1];
        String url = args[2];
        int concurrency = Integer.parseInt(args[3]);
        int seconds = Integer.parseInt(args[4]);
        String token = args[5];
        String bodyTemplate = args.length > 6 ? args[6] : null;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        // 初值用纳秒取模，保证同一服务上重复运行不会撞到上一轮用过的批次号
        AtomicLong seq = new AtomicLong(System.nanoTime() % 1_000_000_000L);
        AtomicLong ok = new AtomicLong();
        AtomicLong fail = new AtomicLong();
        ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();
        List<List<Long>> perThread = new ArrayList<>();

        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();

        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < concurrency; t++) {
            List<Long> latencies = new ArrayList<>(1 << 16);
            perThread.add(latencies);
            workers.add(Thread.ofVirtual().start(() -> {
                while (System.nanoTime() < deadline) {
                    long id = seq.incrementAndGet();
                    String body = bodyTemplate == null ? null : bodyTemplate.replace("${seq}", Long.toString(id));
                    HttpRequest.Builder builder = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(30))
                            .header("Authorization", "Bearer " + token);
                    if ("POST".equalsIgnoreCase(method)) {
                        builder.header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
                    } else {
                        builder.GET();
                    }

                    long start = System.nanoTime();
                    try {
                        HttpResponse<Void> response = client.send(builder.build(),
                                HttpResponse.BodyHandlers.discarding());
                        long micros = (System.nanoTime() - start) / 1000;
                        if (response.statusCode() >= 200 && response.statusCode() < 400) {
                            ok.incrementAndGet();
                            latencies.add(micros);
                        } else {
                            fail.incrementAndGet();
                            if (errors.size() < 5) {
                                errors.add("HTTP " + response.statusCode());
                            }
                        }
                    } catch (Exception e) {
                        fail.incrementAndGet();
                        if (errors.size() < 5) {
                            errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                        }
                    }
                }
            }));
        }

        for (Thread worker : workers) {
            worker.join();
        }

        long[] all = new long[ok.intValue()];
        int pos = 0;
        for (List<Long> latencies : perThread) {
            for (Long value : latencies) {
                if (pos < all.length) {
                    all[pos++] = value;
                }
            }
        }
        Arrays.sort(all, 0, pos);

        long totalMs = seconds * 1000L;
        System.out.printf("%-22s conc=%-3d total=%-7d ok=%-7d fail=%-5d qps=%-8.1f p50=%-6.1f p90=%-6.1f p95=%-6.1f p99=%-7.1f max=%.1f ms%n",
                name, concurrency, ok.get() + fail.get(), ok.get(), fail.get(),
                ok.get() * 1000.0 / Math.max(1, totalMs),
                pct(all, pos, 50) / 1000.0, pct(all, pos, 90) / 1000.0,
                pct(all, pos, 95) / 1000.0, pct(all, pos, 99) / 1000.0,
                pct(all, pos, 100) / 1000.0);
        if (!errors.isEmpty()) {
            System.out.println("  错误样例: " + errors);
        }
    }

    private static long pct(long[] sorted, int count, int percentile) {
        if (count == 0) {
            return 0;
        }
        int index = (int) Math.ceil(percentile / 100.0 * count) - 1;
        return sorted[Math.max(0, Math.min(index, count - 1))];
    }
}
