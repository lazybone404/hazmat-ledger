import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 写链路压测：入库、领用（提交→审批→出库→核销）、盘点（建单→录入→提交→确认）。
 *
 * <p>与 {@link Bench} 的区别：Bench 打单个请求，这里打完整业务链路，测的是
 * 「一次业务动作要发几次请求、事务与乐观锁在并发下的实际成本」。
 *
 * <pre>
 *   java WritePathBench.java &lt;baseUrl&gt; &lt;scenario&gt; &lt;chemicalId&gt; &lt;concurrency&gt; &lt;seconds&gt;
 *   scenario: inbound | requisition | stocktake | rollback
 * </pre>
 *
 * <p>{@code rollback} 用「报废量大于批次余量」触发扣减失败，测的是事务回滚路径。
 * 出库链路不能用来测回滚：{@code StockService.issue} 对库存不足的语义是**部分出库**
 * 而不是抛异常，超量领用会成功发出剩余量，不会回滚。
 * 这个场景本来就会被业务拒绝，所以「成功」定义为拿到 4xx，看的是拒绝路径的延迟与一致性。
 *
 * <p>前置条件：应用已启动、种子账号仍在（admin / approver1 / alice），
 * 目标试剂有库存。密码用 {@code HAZMAT_BENCH_PASSWORD} 覆盖。
 */
public class WritePathBench {

    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern REQ_NO = Pattern.compile("\"reqNo\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern STOCKTAKE_NO = Pattern.compile("\"stocktakeNo\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ITEM_ID = Pattern.compile("\"itemId\"\\s*:\\s*(\\d+)");
    private static final Pattern BOOK_QTY = Pattern.compile("\"bookQuantity\"\\s*:\\s*([0-9.]+)");
    /** 入库响应的第一个 id 就是批次 id（BatchResponse 的 id 是首字段） */
    private static final Pattern BATCH_ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private static String baseUrl;
    private static String adminToken;
    private static String approverToken;
    private static String userToken;
    private static Long chemicalId;
    /** rollback 场景专用的批次：余量 1，报废 1000 必然被扣减条件拒绝 */
    private static Long rollbackBatchId;
    /** 统计压测窗口内的状态码分布；登录阶段不计 */
    private static volatile boolean recordingStatus;
    private static final ConcurrentHashMap<Integer, AtomicLong> STATUS_COUNTS = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("用法: java WritePathBench.java <baseUrl> <scenario> <chemicalId> <concurrency> <seconds>");
            System.exit(2);
        }
        baseUrl = args[0].replaceAll("/+$", "");
        String scenario = args[1];
        chemicalId = Long.parseLong(args[2]);
        int concurrency = Integer.parseInt(args[3]);
        int seconds = Integer.parseInt(args[4]);
        String password = System.getenv().getOrDefault("HAZMAT_BENCH_PASSWORD", "ChangeMe123!");

        adminToken = login("admin", password);
        approverToken = login("approver1", password);
        userToken = login("alice", password);

        AtomicLong seq = new AtomicLong(System.nanoTime() % 1_000_000_000L);
        if ("rollback".equals(scenario)) {
            rollbackBatchId = prepareRollbackBatch(seq.incrementAndGet());
        }
        recordingStatus = true;
        AtomicLong ok = new AtomicLong();
        AtomicLong fail = new AtomicLong();
        AtomicLong requests = new AtomicLong();
        ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();
        List<List<Long>> perThread = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();

        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < concurrency; t++) {
            List<Long> latencies = new ArrayList<>(1 << 15);
            perThread.add(latencies);
            workers.add(Thread.ofVirtual().start(() -> {
                while (System.nanoTime() < deadline) {
                    long id = seq.incrementAndGet();
                    long startedAt = System.nanoTime();
                    try {
                        boolean success = switch (scenario) {
                            case "inbound" -> inbound(id, requests);
                            case "requisition" -> requisition(id, requests);
                            case "stocktake" -> stocktake(requests);
                            case "rollback" -> rollback(id, requests);
                            default -> throw new IllegalArgumentException("未知场景: " + scenario);
                        };
                        long micros = (System.nanoTime() - startedAt) / 1000;
                        if (success) {
                            ok.incrementAndGet();
                            latencies.add(micros);
                        } else {
                            fail.incrementAndGet();
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
        System.out.printf("%-12s conc=%-3d chains=%-6d ok=%-6d fail=%-5d http=%-7d qps=%-7.1f "
                        + "p50=%-6.1f p90=%-6.1f p95=%-6.1f p99=%-7.1f max=%.1f ms%n",
                scenario, concurrency, ok.get() + fail.get(), ok.get(), fail.get(), requests.get(),
                ok.get() * 1000.0 / Math.max(1, totalMs),
                pct(all, pos, 50) / 1000.0, pct(all, pos, 90) / 1000.0,
                pct(all, pos, 95) / 1000.0, pct(all, pos, 99) / 1000.0,
                pct(all, pos, 100) / 1000.0);
        if (!errors.isEmpty()) {
            System.out.println("  错误样例: " + errors);
        }
        StringBuilder statuses = new StringBuilder();
        STATUS_COUNTS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> statuses.append(e.getKey()).append('=').append(e.getValue().get()).append(' '));
        System.out.println("  状态分布: " + statuses.toString().trim());
    }

    private static boolean inbound(long seq, AtomicLong requests) throws Exception {
        String body = """
                {"batchNo":"BENCH-%d","quantity":1,"location":"压测柜","expireDate":"2027-12-31"}
                """.formatted(seq);
        return call("POST", "/api/chemicals/" + chemicalId + "/batches", adminToken, body, requests).ok();
    }

    private static boolean requisition(long seq, AtomicLong requests) throws Exception {
        String submit = """
                {"chemicalId":%d,"quantity":0.001,"purpose":"压测-%d"}
                """.formatted(chemicalId, seq);
        Response created = call("POST", "/api/requisitions", userToken, submit, requests);
        if (!created.ok()) {
            return false;
        }
        String reqNo = extract(REQ_NO, created.body());
        if (reqNo == null) {
            return false;
        }
        Response reviewed = call("POST", "/api/requisitions/" + reqNo + "/review", approverToken,
                "{\"approved\":true,\"comment\":\"压测审批\"}", requests);
        if (!reviewed.ok()) {
            return false;
        }
        Response issued = call("POST", "/api/requisitions/" + reqNo + "/issue", adminToken, "", requests);
        if (!issued.ok()) {
            return false;
        }
        return call("POST", "/api/requisitions/" + reqNo + "/close", adminToken, "", requests).ok();
    }

    private static boolean stocktake(AtomicLong requests) throws Exception {
        Response created = call("POST", "/api/stocktakes", adminToken,
                "{\"chemicalIds\":[" + chemicalId + "],\"remark\":\"压测盘点\"}", requests);
        if (!created.ok()) {
            return false;
        }
        String stocktakeNo = extract(STOCKTAKE_NO, created.body());
        if (stocktakeNo == null) {
            return false;
        }
        Response detail = call("GET", "/api/stocktakes/" + stocktakeNo, adminToken, null, requests);
        if (!detail.ok()) {
            return false;
        }
        List<Long> itemIds = extractAll(ITEM_ID, detail.body());
        List<String> bookQuantities = extractTexts(BOOK_QTY, detail.body());
        for (int i = 0; i < itemIds.size() && i < bookQuantities.size(); i++) {
            String body = "{\"actualQuantity\":" + bookQuantities.get(i) + ",\"note\":\"压测\"}";
            Response recorded = call("PUT",
                    "/api/stocktakes/" + stocktakeNo + "/items/" + itemIds.get(i),
                    adminToken, body, requests);
            if (!recorded.ok()) {
                return false;
            }
        }
        if (!call("POST", "/api/stocktakes/" + stocktakeNo + "/submit", adminToken, "", requests).ok()) {
            return false;
        }
        return call("POST", "/api/stocktakes/" + stocktakeNo + "/confirm", adminToken, "", requests).ok();
    }

    /**
     * 建一个余量为 1 的专用批次，作为回滚场景的靶子。
     *
     * 不能用「领用量大于库存」来测回滚：出库允许部分发货，超量领用会成功发出剩余量，
     * 根本不回滚。报废走的是同一条条件扣减，余量不够时直接抛业务异常，才真的回滚。
     */
    private static Long prepareRollbackBatch(long seq) throws Exception {
        String body = """
                {"batchNo":"BENCH-RB-%d","quantity":1,"location":"压测柜","expireDate":"2027-12-31"}
                """.formatted(seq);
        Response created = call("POST", "/api/chemicals/" + chemicalId + "/batches", adminToken, body,
                new AtomicLong());
        if (!created.ok()) {
            throw new IllegalStateException("准备回滚批次失败: HTTP " + created.status() + " " + created.body());
        }
        String id = extract(BATCH_ID, created.body());
        if (id == null) {
            throw new IllegalStateException("回滚批次响应里没有 id: " + created.body());
        }
        return Long.parseLong(id);
    }

    /**
     * 事务回滚路径：报废量（1000）远大于批次余量（1），条件扣减返回 0，Service 抛业务异常。
     * 「成功」定义为请求被明确拒绝（4xx），而不是 5xx 或超时。
     */
    private static boolean rollback(long seq, AtomicLong requests) throws Exception {
        String body = """
                {"quantity":1000,"reason":"压测超量报废-%d"}
                """.formatted(seq);
        Response response = call("POST",
                "/api/chemicals/" + chemicalId + "/batches/" + rollbackBatchId + "/scrap",
                adminToken, body, requests);
        return response.status() >= 400 && response.status() < 500;
    }

    private static String login(String username, String password) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        Response response = call("POST", "/api/auth/login", null, body, new AtomicLong());
        if (!response.ok()) {
            throw new IllegalStateException("登录失败 " + username + ": HTTP " + response.status());
        }
        String token = extract(TOKEN, response.body());
        if (token == null) {
            throw new IllegalStateException("登录响应里没有 token: " + response.body());
        }
        return token;
    }

    private static Response call(String method, String path, String token, String body,
                                 AtomicLong requests) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        }
        requests.incrementAndGet();
        HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (recordingStatus) {
            STATUS_COUNTS.computeIfAbsent(response.statusCode(), k -> new AtomicLong()).incrementAndGet();
        }
        return new Response(response.statusCode(), response.body());
    }

    private static String extract(Pattern pattern, String body) {
        Matcher matcher = pattern.matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static List<Long> extractAll(Pattern pattern, String body) {
        List<Long> values = new ArrayList<>();
        Matcher matcher = pattern.matcher(body);
        while (matcher.find()) {
            values.add(Long.parseLong(matcher.group(1)));
        }
        return values;
    }

    private static List<String> extractTexts(Pattern pattern, String body) {
        List<String> values = new ArrayList<>();
        Matcher matcher = pattern.matcher(body);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static long pct(long[] sorted, int count, int percentile) {
        if (count == 0) {
            return 0;
        }
        int index = (int) Math.ceil(percentile / 100.0 * count) - 1;
        return sorted[Math.max(0, Math.min(index, count - 1))];
    }

    private record Response(int status, String body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }
    }
}
