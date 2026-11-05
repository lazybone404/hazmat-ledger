package com.lazybone.hazmat.msds;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazybone.hazmat.entity.Chemical;
import com.lazybone.hazmat.entity.MsdsChunk;
import com.lazybone.hazmat.msds.ai.HashingEmbeddingClient;
import com.lazybone.hazmat.msds.ai.VectorCodec;
import com.lazybone.hazmat.repository.ChemicalRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3：单块上限 200 / 400 / 800 字的对照实验。
 *
 * 要回答的问题不是「哪个数字的 Hit@5 高」，而是：把节再切细，会不会把答案切散；
 * 放宽到 800，会不会让一个块里混进前后两节、反而稀释相关段落的权重。
 * 400 是从 512 token 的中文模型倒推的默认值，这个实验给它一个本项目的实测依据。
 *
 * <b>做法</b>：不写库、不启动编码线程，用真实的 {@link MsdsChunker}、
 * {@link MsdsLiteralIndex}、{@link MsdsVectorIndex} 在内存里各建一份索引。
 * 三档共用同一批文件、同一套题、同一份名称表（台账名，缺省时取文件里的「中文名称」），
 * 所以差异只来自切块粒度。向量路固定走 {@link HashingEmbeddingClient}——
 * 它虽弱，但对三档是同一把尺子；换 bge-m3 后应重跑，结论可能变。
 *
 * <b>指标口径</b>与 {@link MsdsRetrievalMetricsTest} 一致：匹配只看
 * {@code (casNo, sectionNo)}，候选深度用生产的 50，指标取前 10。
 * 因此同一档（400）的数字应与那边接近；若有 ±1 的出入，先看名称来源是否一致。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:msds-chunk-size;DB_CLOSE_DELAY=-1",
        "hazmat.ai.encode-on-startup=false"
})
class MsdsChunkSizeSweepTest {

    private static final int[] SIZES = {200, 400, 800};
    private static final int K = 5;
    private static final int METRIC_DEPTH = 10;
    private static final Pattern NAME_PATTERN = Pattern.compile("中文名称：\\s*([^\\s（(]+)");

    @Autowired
    private ChemicalRepository chemicalRepository;

    @Autowired
    private HashingEmbeddingClient embeddingClient;

    @Autowired
    private MsdsRetrievalProperties retrieval;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void comparesChunkSizesOnTheSameQuestionSet() throws Exception {
        List<Question> answerable = loadQuestions().questions().stream()
                .filter(question -> !question.answers().isEmpty())
                .toList();
        Map<String, String> registryNames = chemicalRepository.findAll().stream()
                .collect(Collectors.toMap(Chemical::getCasNo, Chemical::getName, (a, b) -> a));

        System.out.println("\n=== E3 切块粒度对照（题数=" + answerable.size()
                + "，向量=" + embeddingClient.name()
                + "，权重=" + retrieval.getLiteralWeight() + ":" + retrieval.getVectorWeight()
                + "，rrfK=" + retrieval.getRrfK() + "）===");

        Map<Integer, Dataset> datasets = new LinkedHashMap<>();
        Map<Integer, Metrics> literal = new LinkedHashMap<>();
        Map<Integer, Metrics> vector = new LinkedHashMap<>();
        Map<Integer, Metrics> fused = new LinkedHashMap<>();

        for (int size : SIZES) {
            Dataset dataset = buildDataset(size, registryNames);
            datasets.put(size, dataset);
            literal.put(size, measure(answerable, dataset::literalTop));
            vector.put(size, measure(answerable, dataset::vectorTop));
            fused.put(size, measure(answerable, dataset::fusedTop));
        }

        printStats(datasets);
        printMetrics("纯字面", literal);
        printMetrics("纯向量（local-hash）", vector);
        printMetrics("融合 " + fmt(retrieval.getLiteralWeight()) + ":"
                + fmt(retrieval.getVectorWeight()), fused);

        System.out.println("\n400 字档的融合失手题: " + datasets.get(400).fusedMisses(answerable));
        System.out.println("\n说明：每档单独切块建索引；同一节被切多块时命中任一块即算该节命中。");

        // 切块规则不因上限变化而丢节：每份文件仍然覆盖 1–16 节
        for (Dataset dataset : datasets.values()) {
            assertThat(dataset.sectionCoverage())
                    .as("每个上限下，每份 MSDS 都应覆盖 1–16 节")
                    .allSatisfy((cas, sections) -> assertThat(sections)
                            .containsExactlyInAnyOrderElementsOf(
                                    java.util.stream.IntStream.rangeClosed(1, 16)
                                            .boxed().toList()));
        }
        // 上限越小，块数不可能更少（单段落/表格不可拆，所以可能相等）
        assertThat(datasets.get(200).chunks().size())
                .isGreaterThanOrEqualTo(datasets.get(400).chunks().size());
        assertThat(datasets.get(400).chunks().size())
                .isGreaterThanOrEqualTo(datasets.get(800).chunks().size());
        // 与其它评测同一条底线：默认 400 下融合不能比字面差
        assertThat(fused.get(400).hitAt5())
                .as("400 字档的融合 Hit@5 不应低于同档字面基线")
                .isGreaterThanOrEqualTo(literal.get(400).hitAt5());
    }

    private Dataset buildDataset(int maxChars, Map<String, String> registryNames) throws Exception {
        List<MsdsChunk> chunks = new ArrayList<>();
        // 生产索引在 refreshFromDatabase 里从块前缀把名称捞回来传给字面索引；
        // 这里同样记录「实际用到的名称」，否则字面打分少了名称 boost，与生产不可比
        Map<String, String> usedNames = new LinkedHashMap<>();
        long id = 1;
        for (Resource resource : msdsResources()) {
            String filename = resource.getFilename();
            String casNo = filename.substring(0, filename.length() - ".md".length());
            String markdown = resource.getContentAsString(StandardCharsets.UTF_8);
            List<MsdsChunker.Chunk> parsed = MsdsChunker.split(markdown, maxChars);
            String chemicalName = registryNames.get(casNo);
            if (chemicalName == null) {
                chemicalName = extractChemicalName(parsed);
            }
            if (chemicalName != null) {
                usedNames.put(casNo, chemicalName);
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
                        .vector(VectorCodec.encode(embeddingClient.name(), embed(content)))
                        .build());
            }
        }
        MsdsLiteralIndex literalIndex = MsdsLiteralIndex.build(chunks, usedNames);
        MsdsVectorIndex vectorIndex = MsdsVectorIndex.build(chunks, embeddingClient);
        return new Dataset(chunks, literalIndex, vectorIndex);
    }

    private float[] embed(String text) {
        return embeddingClient.embed(List.of(text)).get(0);
    }

    private Resource[] msdsResources() throws Exception {
        return new PathMatchingResourcePatternResolver().getResources("classpath*:msds/*.md");
    }

    /** 与 {@code MsdsIndexService#extractChemicalName} 同式：档案里没有这个 CAS 时退回文件头 */
    private static String extractChemicalName(List<MsdsChunker.Chunk> chunks) {
        for (MsdsChunker.Chunk chunk : chunks) {
            Matcher matcher = NAME_PATTERN.matcher(chunk.content());
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private void printStats(Map<Integer, Dataset> datasets) {
        System.out.println("\n| 单块上限 | 文件数 | 块数 | 平均块长 | 最长块 |");
        System.out.println("|---:|---:|---:|---:|---:|");
        datasets.forEach((size, dataset) -> {
            List<MsdsChunk> chunks = dataset.chunks();
            double avg = chunks.stream().mapToInt(chunk -> chunk.getContent().length()).average().orElse(0);
            int max = chunks.stream().mapToInt(chunk -> chunk.getContent().length()).max().orElse(0);
            System.out.printf("| %d | %d | %d | %.0f | %d |%n",
                    size, dataset.sectionCoverage().size(), chunks.size(), avg, max);
        });
    }

    private void printMetrics(String path, Map<Integer, Metrics> bySize) {
        System.out.println("\n" + path);
        System.out.println("| 单块上限 | Hit@1 | Hit@5 | MRR@10 | Recall@5 |");
        System.out.println("|---:|---:|---:|---:|---:|");
        bySize.forEach((size, metrics) -> System.out.printf("| %d | %s | %s | %.3f | %.3f |%n",
                size,
                ratio(metrics.hitAt1(), metrics.total()),
                ratio(metrics.hitAt5(), metrics.total()),
                metrics.mrr(),
                metrics.recallAt5()));
    }

    private static String fmt(double value) {
        return value == Math.rint(value) ? String.valueOf((int) value) : String.valueOf(value);
    }

    private static String ratio(int hit, int total) {
        return total == 0 ? "0/0" : String.format("%d/%d (%.1f%%)", hit, total, 100.0 * hit / total);
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

    /** 一份切块档位的全部内存状态；查询时按题惰性算 query 向量 */
    private final class Dataset {

        private final List<MsdsChunk> chunks;
        private final MsdsLiteralIndex literalIndex;
        private final MsdsVectorIndex vectorIndex;

        private Dataset(List<MsdsChunk> chunks,
                        MsdsLiteralIndex literalIndex,
                        MsdsVectorIndex vectorIndex) {
            this.chunks = chunks;
            this.literalIndex = literalIndex;
            this.vectorIndex = vectorIndex;
        }

        private List<MsdsChunk> chunks() {
            return chunks;
        }

        private Map<String, Set<Integer>> sectionCoverage() {
            return chunks.stream().collect(Collectors.groupingBy(
                    MsdsChunk::getCasNo,
                    Collectors.mapping(MsdsChunk::getSectionNo, Collectors.toSet())));
        }

        private List<Key> literalTop(Question question) {
            return literalIndex.search(question.query(), null, retrieval.effectiveDepth()).stream()
                    .limit(METRIC_DEPTH)
                    .map(hit -> Key.of(hit.chunk()))
                    .toList();
        }

        private List<Key> vectorTop(Question question) {
            return vectorIndex.search(embed(question.query()), null, embeddingClient.minScore()).stream()
                    .limit(METRIC_DEPTH)
                    .map(hit -> Key.of(hit.chunk()))
                    .toList();
        }

        private List<Key> fusedTop(Question question) {
            List<MsdsLiteralIndex.Hit> literal =
                    literalIndex.search(question.query(), null, retrieval.effectiveDepth());
            List<MsdsVectorIndex.Hit> vector =
                    vectorIndex.search(embed(question.query()), null, embeddingClient.minScore());
            return fuse(literal, vector).stream()
                    .limit(METRIC_DEPTH)
                    .map(Key::of)
                    .toList();
        }

        /** 与 {@code MsdsSearchService#fuse} 同式；本实验不落 {@link com.lazybone.hazmat.dto.MsdsHit} */
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
                    .sorted(java.util.Comparator
                            .comparingDouble((Scored scored) -> scored.score).reversed()
                            .thenComparing(scored -> scored.chunk.getCasNo())
                            .thenComparingInt(scored -> scored.chunk.getSectionNo())
                            .thenComparingInt(scored -> scored.chunk.getPartNo()))
                    .map(scored -> scored.chunk)
                    .toList();
        }

        private List<String> fusedMisses(List<Question> questions) {
            return questions.stream()
                    .filter(question -> {
                        int rank = firstMatchRank(fusedTop(question), question);
                        return rank == 0 || rank > K;
                    })
                    .map(Question::id)
                    .toList();
        }
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
