package com.lazybone.hazmat.msds;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazybone.hazmat.entity.Chemical;
import com.lazybone.hazmat.entity.MsdsChunk;
import com.lazybone.hazmat.msds.ai.EmbeddingClient;
import com.lazybone.hazmat.msds.ai.HashingEmbeddingClient;
import com.lazybone.hazmat.msds.ai.HttpEmbeddingClient;
import com.lazybone.hazmat.msds.ai.VectorCodec;
import com.lazybone.hazmat.repository.ChemicalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E4：local-hash 与 bge-m3 在同一题集上的对照。
 *
 * 与 {@link MsdsChunkSizeSweepTest} 共用一套「内存切块 + 内存建索引」的装置，
 * 区别只是把 embedding 换成真实模型。要回答三个问题：
 *   1. 语义向量的 Hit@5 / MRR 比本地哈希高多少；
 *   2. 在语义向量下，3:1 的权重还是不是最优；
 *   3. 融合的首条精度（Hit@1）能不能从 6/20 拉起来。
 *
 * <b>权重口径</b>：本测试的「融合」指标固定按 3:1 打印，与 `tools/bench/embedding-compare-result.md`
 * 的历史输出对齐；权重扫描表覆盖 1:1、1:2 等档位。生产代码在语义模型下已默认 1:2，
 * 见 {@link MsdsWeightProfileTest}。
 *
 * <b>默认不跑</b>：需要本机 Ollama 已启动并拉过 bge-m3。显式打开才执行：
 * <pre>
 *   ollama serve
 *   ollama pull bge-m3
 *   HAZMAT_E4=true ./mvnw -Dtest=MsdsEmbeddingCompareTest test
 * </pre>
 * 用环境变量而不是 {@code -D}，因为 surefire 不保证把 Maven 命令行属性传进 forked JVM，
 * 而环境变量一定会被继承。云端 embedding（DashScope）也能用同一套流程，
 * 改 {@code hazmat.ai.base-url} 与 key 即可；但本测试按 Ollama 的 1024 维写死，换模型要同步改维度。
 */
@EnabledIfEnvironmentVariable(named = "HAZMAT_E4", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:msds-embedding-compare;DB_CLOSE_DELAY=-1",
        "hazmat.ai.encode-on-startup=false",
        "hazmat.ai.enabled=true",
        "hazmat.ai.base-url=http://localhost:11434/v1",
        "hazmat.ai.api-key=ollama",
        "hazmat.ai.embedding-model=bge-m3",
        "hazmat.ai.embedding-dimensions=1024",
        // 首次请求要加载约 1.2GB 的模型权重，3 秒超时必然失败
        "hazmat.ai.timeout-ms=120000"
})
class MsdsEmbeddingCompareTest {

    private static final int CHUNK_MAX_CHARS = 400;
    private static final int K = 5;
    private static final int METRIC_DEPTH = 10;
    /** 与 MsdsChunker.MAX_CHARS 的生产值一致，E3 已确认当前语料下三档等价 */
    private static final Pattern NAME_PATTERN = Pattern.compile("中文名称：\\s*([^\\s（(]+)");

    @Autowired
    private ChemicalRepository chemicalRepository;

    @Autowired
    private HashingEmbeddingClient localClient;

    @Autowired
    private HttpEmbeddingClient remoteClient;

    @Autowired
    private MsdsRetrievalProperties retrieval;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void comparesLocalHashAndBgeM3() throws Exception {
        List<Question> answerable = loadQuestions().questions().stream()
                .filter(question -> !question.answers().isEmpty())
                .toList();
        Map<String, String> registryNames = chemicalRepository.findAll().stream()
                .collect(Collectors.toMap(Chemical::getCasNo, Chemical::getName, (a, b) -> a));
        List<MsdsChunk> baseChunks = buildChunks(registryNames);

        System.out.println("\n=== E4 embedding 对照（题数=" + answerable.size()
                + "，块数=" + baseChunks.size()
                + "，字面:向量=" + retrieval.getLiteralWeight() + ":" + retrieval.getVectorWeight()
                + "，rrfK=" + retrieval.getRrfK() + "）===");

        Map<String, ModelRun> runs = new LinkedHashMap<>();
        runs.put(localClient.name(), run(localClient, baseChunks, answerable));
        runs.put(remoteClient.name(), run(remoteClient, baseChunks, answerable));

        printMetrics("纯向量", runs, run -> run.metrics().vector());
        printMetrics("融合 " + fmt(retrieval.getLiteralWeight()) + ":"
                + fmt(retrieval.getVectorWeight()), runs, run -> run.metrics().fused());
        printWeightSweep(runs);

        System.out.println("\n说明：两档共用同一批块与题；向量维度不同，但打分都走余弦，"
                + "差异全部来自 embedding 质量。本地哈希无外部依赖，bge-m3 需 Ollama。");

        runs.forEach((model, run) -> {
            assertThat(run.vectorIndex().size())
                    .as("%s 的向量索引应覆盖全部块", model)
                    .isEqualTo(baseChunks.size());
            assertThat(run.metrics().vector().total()).isEqualTo(answerable.size());
        });
    }

    private ModelRun run(EmbeddingClient client, List<MsdsChunk> baseChunks, List<Question> questions) {
        List<MsdsChunk> chunks = baseChunks.stream()
                .map(chunk -> MsdsChunk.builder()
                        .id(chunk.getId())
                        .casNo(chunk.getCasNo())
                        .sectionNo(chunk.getSectionNo())
                        .sectionTitle(chunk.getSectionTitle())
                        .partNo(chunk.getPartNo())
                        .content(chunk.getContent())
                        .textHash(chunk.getTextHash())
                        .build())
                .toList();
        // 名称表与生产一致：从块前缀取（MsdsIndexService#refreshFromDatabase 也是这么恢复的）
        Map<String, String> names = namesFromPrefixes(chunks);

        long start = System.nanoTime();
        List<float[]> vectors = client.embed(chunks.stream().map(MsdsChunk::getContent).toList());
        long embedMillis = (System.nanoTime() - start) / 1_000_000;

        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setVector(VectorCodec.encode(client.name(), vectors.get(i)));
        }
        MsdsLiteralIndex literalIndex = MsdsLiteralIndex.build(chunks, names);
        MsdsVectorIndex vectorIndex = MsdsVectorIndex.build(chunks, client);

        System.out.printf("%n[%s] 编码 %d 块耗时 %,d ms（维度 %d）%n",
                client.name(), chunks.size(), embedMillis, client.dimensions());

        // 查询向量缓存：权重扫描会对同一题反复调融合，不缓存就要重复调 10 倍
        Map<String, float[]> queryVectors = new LinkedHashMap<>();
        ModelRun run = new ModelRun(literalIndex, vectorIndex, null, embedMillis,
                questions, client, queryVectors);

        Metrics vector = measure(questions, question ->
                vectorIndex.search(run.queryVector(question.query()), null, client.minScore()).stream()
                        .limit(METRIC_DEPTH)
                        .map(hit -> Key.of(hit.chunk()))
                        .toList());
        Metrics fused = measure(questions, question -> {
            List<MsdsLiteralIndex.Hit> literal =
                    literalIndex.search(question.query(), null, retrieval.effectiveDepth());
            List<MsdsVectorIndex.Hit> hits =
                    vectorIndex.search(run.queryVector(question.query()), null, client.minScore());
            return fuse(literal, hits).stream().limit(METRIC_DEPTH).map(Key::of).toList();
        });
        return new ModelRun(literalIndex, vectorIndex, new MetricsPair(vector, fused),
                embedMillis, questions, client, queryVectors);
    }

    private List<MsdsChunk> buildChunks(Map<String, String> registryNames) throws Exception {
        List<MsdsChunk> chunks = new ArrayList<>();
        long id = 1;
        for (Resource resource : msdsResources()) {
            String filename = resource.getFilename();
            String casNo = filename.substring(0, filename.length() - ".md".length());
            String markdown = resource.getContentAsString(StandardCharsets.UTF_8);
            List<MsdsChunker.Chunk> parsed = MsdsChunker.split(markdown, CHUNK_MAX_CHARS);
            String chemicalName = registryNames.get(casNo);
            if (chemicalName == null) {
                chemicalName = extractChemicalName(parsed);
            }
            for (MsdsChunker.Chunk chunk : parsed) {
                String content = chemicalName == null
                        ? chunk.content()
                        : "[" + chemicalName + "] " + chunk.content();
                chunks.add(MsdsChunk.builder()
                        .id(id++)
                        .casNo(casNo)
                        .sectionNo(chunk.sectionNo())
                        .sectionTitle(chunk.sectionTitle())
                        .partNo(chunk.partNo())
                        .content(content)
                        .textHash(MsdsChunker.sha256(content))
                        .build());
            }
        }
        return chunks;
    }

    private Resource[] msdsResources() throws Exception {
        return new PathMatchingResourcePatternResolver().getResources("classpath*:msds/*.md");
    }

    private static String extractChemicalName(List<MsdsChunker.Chunk> chunks) {
        for (MsdsChunker.Chunk chunk : chunks) {
            Matcher matcher = NAME_PATTERN.matcher(chunk.content());
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    /** 与 MsdsIndexService 的 SUBJECT_PREFIX 同式 */
    private static Map<String, String> namesFromPrefixes(List<MsdsChunk> chunks) {
        Map<String, String> names = new LinkedHashMap<>();
        Pattern prefix = Pattern.compile("^\\[([^\\]]+)] ");
        for (MsdsChunk chunk : chunks) {
            Matcher matcher = prefix.matcher(chunk.getContent());
            if (matcher.find()) {
                names.putIfAbsent(chunk.getCasNo(), matcher.group(1));
            }
        }
        return names;
    }

    private List<MsdsChunk> fuse(List<MsdsLiteralIndex.Hit> literal,
                                 List<MsdsVectorIndex.Hit> vector) {
        int rrfK = retrieval.getRrfK();
        Map<Long, Scored> fused = new LinkedHashMap<>();
        for (int rank = 0; rank < literal.size(); rank++) {
            MsdsChunk chunk = literal.get(rank).chunk();
            fused.computeIfAbsent(chunk.getId(), key -> new Scored(chunk))
                    .score += retrieval.getLiteralWeight() / (rrfK + rank + 1);
        }
        for (int rank = 0; rank < vector.size(); rank++) {
            MsdsChunk chunk = vector.get(rank).chunk();
            fused.computeIfAbsent(chunk.getId(), key -> new Scored(chunk))
                    .score += retrieval.getVectorWeight() / (rrfK + rank + 1);
        }
        return fused.values().stream()
                .sorted(Comparator.comparingDouble((Scored scored) -> scored.score).reversed()
                        .thenComparing(scored -> scored.chunk.getCasNo())
                        .thenComparingInt(scored -> scored.chunk.getSectionNo())
                        .thenComparingInt(scored -> scored.chunk.getPartNo()))
                .map(scored -> scored.chunk)
                .toList();
    }

    private void printMetrics(String path, Map<String, ModelRun> runs,
                              java.util.function.Function<ModelRun, Metrics> picker) {
        System.out.println("\n" + path);
        System.out.println("| 模型 | Hit@1 | Hit@5 | MRR@10 | Recall@5 | 编码总耗时 |");
        System.out.println("|---|---:|---:|---:|---:|---:|");
        runs.forEach((model, run) -> {
            Metrics metrics = picker.apply(run);
            System.out.printf("| %s | %s | %s | %.3f | %.3f | %,d ms |%n",
                    model,
                    ratio(metrics.hitAt1(), metrics.total()),
                    ratio(metrics.hitAt5(), metrics.total()),
                    metrics.mrr(),
                    metrics.recallAt5(),
                    run.embedMillis());
        });
    }

    private void printWeightSweep(Map<String, ModelRun> runs) {
        System.out.println("\n加权扫描：只改融合权重，候选与题集不变");
        double[][] grid = {
                {1, 0}, {3, 0}, {0, 1},
                {1, 1}, {2, 1}, {3, 1}, {5, 1}, {10, 1},
                {1, 2}, {1, 3}
        };
        runs.forEach((model, run) -> {
            System.out.println("\n模型 " + model);
            System.out.println("| 字面:向量 | Hit@1 | Hit@5 | MRR@10 |");
            System.out.println("|---|---:|---:|---:|");
            for (double[] weights : grid) {
                int hit1 = 0;
                int hit5 = 0;
                double reciprocalRank = 0;
                for (Question question : run.questions()) {
                    List<MsdsChunk> fused = fuseWithWeights(run, question, weights[0], weights[1]);
                    int rank = firstChunkMatchRank(fused, question);
                    if (rank > 0) {
                        reciprocalRank += 1.0 / rank;
                        hit1 += rank == 1 ? 1 : 0;
                        hit5 += rank <= K ? 1 : 0;
                    }
                }
                int total = run.questions().size();
                System.out.printf("| %.0f:%.0f | %d/%d | %d/%d | %.3f |%n",
                        weights[0], weights[1], hit1, total, hit5, total,
                        total == 0 ? 0 : reciprocalRank / total);
            }
        });
    }

    private static int firstChunkMatchRank(List<MsdsChunk> top, Question question) {
        for (int rank = 0; rank < Math.min(top.size(), METRIC_DEPTH); rank++) {
            if (matches(Key.of(top.get(rank)), question)) {
                return rank + 1;
            }
        }
        return 0;
    }

    private List<MsdsChunk> fuseWithWeights(ModelRun run, Question question,
                                            double literalWeight, double vectorWeight) {
        int rrfK = retrieval.getRrfK();
        Map<Long, Scored> fused = new LinkedHashMap<>();
        List<MsdsLiteralIndex.Hit> literal =
                run.literalIndex().search(question.query(), null, retrieval.effectiveDepth());
        List<MsdsVectorIndex.Hit> vector =
                run.vectorIndex().search(run.queryVector(question.query()), null,
                        run.client().minScore());
        for (int rank = 0; rank < literal.size(); rank++) {
            MsdsChunk chunk = literal.get(rank).chunk();
            fused.computeIfAbsent(chunk.getId(), key -> new Scored(chunk))
                    .score += literalWeight / (rrfK + rank + 1);
        }
        for (int rank = 0; rank < vector.size(); rank++) {
            MsdsChunk chunk = vector.get(rank).chunk();
            fused.computeIfAbsent(chunk.getId(), key -> new Scored(chunk))
                    .score += vectorWeight / (rrfK + rank + 1);
        }
        return fused.values().stream()
                .sorted(Comparator.comparingDouble((Scored scored) -> scored.score).reversed()
                        .thenComparing(scored -> scored.chunk.getCasNo())
                        .thenComparingInt(scored -> scored.chunk.getSectionNo())
                        .thenComparingInt(scored -> scored.chunk.getPartNo()))
                .map(scored -> scored.chunk)
                .toList();
    }

    private static Metrics measure(List<Question> questions,
                                   java.util.function.Function<Question, List<Key>> retriever) {
        int hit1 = 0;
        int hit5 = 0;
        double reciprocalRank = 0;
        double recall = 0;
        for (Question question : questions) {
            List<Key> top = retriever.apply(question);
            int first = firstMatchRank(top, question);
            if (first > 0) {
                reciprocalRank += 1.0 / first;
                hit1 += first == 1 ? 1 : 0;
                hit5 += first <= K ? 1 : 0;
            }
            recall += recallAt(top, question, K);
        }
        int total = questions.size();
        return new Metrics(total, hit1, hit5,
                total == 0 ? 0 : reciprocalRank / total,
                total == 0 ? 0 : recall / total);
    }

    private static int firstMatchRank(List<Key> top, Question question) {
        for (int rank = 0; rank < Math.min(top.size(), METRIC_DEPTH); rank++) {
            if (matches(top.get(rank), question)) {
                return rank + 1;
            }
        }
        return 0;
    }

    private static double recallAt(List<Key> top, Question question, int k) {
        Set<Key> retrieved = top.stream().limit(k).collect(Collectors.toSet());
        long covered = question.answers().stream()
                .map(answer -> new Key(answer.casNo(), answer.sectionNo()))
                .filter(retrieved::contains)
                .count();
        return (double) covered / question.answers().size();
    }

    private static boolean matches(Key key, Question question) {
        return question.answers().stream()
                .anyMatch(answer -> answer.casNo().equals(key.casNo())
                        && answer.sectionNo() == key.sectionNo());
    }

    private EvalFile loadQuestions() throws Exception {
        try (InputStream in = new ClassPathResource("msds-eval/questions.json").getInputStream()) {
            return objectMapper.readValue(in, EvalFile.class);
        }
    }

    private static String fmt(double value) {
        return value == Math.rint(value) ? String.valueOf((int) value) : String.valueOf(value);
    }

    private static String ratio(int hit, int total) {
        return total == 0 ? "0/0" : String.format("%d/%d (%.1f%%)", hit, total, 100.0 * hit / total);
    }

    /** 一次模型跑出来的全部中间状态；权重扫描要复用候选与查询向量，避免重复调模型 */
    private record ModelRun(MsdsLiteralIndex literalIndex,
                            MsdsVectorIndex vectorIndex,
                            MetricsPair metrics,
                            long embedMillis,
                            List<Question> questions,
                            EmbeddingClient client,
                            Map<String, float[]> queryVectors) {

        private float[] queryVector(String query) {
            return queryVectors.computeIfAbsent(query,
                    key -> client.embed(List.of(key)).get(0));
        }
    }

    private record MetricsPair(Metrics vector, Metrics fused) {
    }

    private static final class Scored {
        private final MsdsChunk chunk;
        private double score;

        private Scored(MsdsChunk chunk) {
            this.chunk = chunk;
        }
    }

    private record Key(String casNo, int sectionNo) {
        static Key of(MsdsChunk chunk) {
            return new Key(chunk.getCasNo(), chunk.getSectionNo());
        }
    }

    private record Metrics(int total, int hitAt1, int hitAt5, double mrr, double recallAt5) {
    }

    record EvalFile(List<Question> questions) {
    }

    record Question(String id, String type, String query, List<Answer> answers) {
    }

    record Answer(String casNo, int sectionNo) {
    }
}
