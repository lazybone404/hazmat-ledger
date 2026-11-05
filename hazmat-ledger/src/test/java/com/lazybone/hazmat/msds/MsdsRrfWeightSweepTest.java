package com.lazybone.hazmat.msds;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazybone.hazmat.entity.MsdsChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RRF 权重的敏感性扫描：同一批候选、同一套题，只改字面/向量的权重比。
 *
 * 为什么需要这个测试：{@code hazmat.msds.retrieval.literal-weight=3.0} 是在
 * 无模型 key、向量路走本地哈希的条件下调出来的。换 bge-m3 后向量路质量变化很大，
 * 这个比例很可能不再是 3:1。与其拍脑袋，不如把权重作为变量扫一遍。
 *
 * 注意：本测试无外部依赖，向量路是 {@link com.lazybone.hazmat.msds.ai.HashingEmbeddingClient}。
 * 它只能回答「当前兜底模型下哪个比例最稳」，不能替代 bge-m3 下的复测。
 * 输出里的模型名要一起看。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:msds-rrf-sweep;DB_CLOSE_DELAY=-1",
        "hazmat.ai.encode-on-startup=false"
})
class MsdsRrfWeightSweepTest {

    private static final int K = 5;

    @Autowired
    private MsdsIndexService indexService;

    @Autowired
    private MsdsVectorService vectorService;

    @Autowired
    private MsdsRetrievalProperties retrieval;

    @Autowired
    private ObjectMapper objectMapper;

    private List<Question> answerable;

    @BeforeEach
    void prepare() throws Exception {
        vectorService.encodePending(0);
        try (InputStream in = new ClassPathResource("msds-eval/questions.json").getInputStream()) {
            EvalFile file = objectMapper.readValue(in, EvalFile.class);
            answerable = file.questions().stream()
                    .filter(question -> !question.answers().isEmpty())
                    .toList();
        }
    }

    @Test
    void sweepWeightsAndReportHitRate() {
        // 每题只查一次候选，之后所有权重档复用，避免重复计算污染耗时与结果
        List<Candidates> candidates = new ArrayList<>();
        int depth = retrieval.effectiveDepth();
        for (Question question : answerable) {
            List<MsdsChunk> literal = indexService.literalIndex()
                    .search(question.query(), null, depth).stream()
                    .map(MsdsLiteralIndex.Hit::chunk).toList();
            List<MsdsChunk> vector = vectorService.search(question.query(), null).hits()
                    .stream().limit(depth).map(MsdsVectorIndex.Hit::chunk).toList();
            candidates.add(new Candidates(question, literal, vector));
        }

        System.out.println("\n=== RRF 权重扫描（K=" + K + "，深度=" + depth
                + "，rrfK=" + retrieval.getRrfK()
                + "，向量模型=" + vectorService.activeClient().name() + "）===");
        System.out.println("| 字面:向量 | Hit@" + K + " | 失手题 |");
        System.out.println("|---|---:|---|");

        double[][] grid = {
                {1, 0}, {3, 0}, {0, 1},
                {1, 1}, {3, 1}, {5, 1}, {10, 1},
                {1, 2}, {1, 3}
        };
        int literalOnly = 0;
        int currentRatio = 0;
        for (double[] weights : grid) {
            int hits = 0;
            List<String> misses = new ArrayList<>();
            for (Candidates candidate : candidates) {
                List<MsdsChunk> fused = fuse(candidate, weights[0], weights[1]);
                boolean hit = fused.stream().limit(K)
                        .anyMatch(chunk -> matches(chunk, candidate.question()));
                if (hit) {
                    hits++;
                } else {
                    misses.add(candidate.question().id());
                }
            }
            System.out.printf("| %.0f:%.0f | %d/%d | %s |%n",
                    weights[0], weights[1], hits, candidates.size(), misses);
            if (weights[0] == 3 && weights[1] == 0) {
                literalOnly = hits;
            }
            if (weights[0] == 3 && weights[1] == 1) {
                currentRatio = hits;
            }
        }

        // 与 MsdsRetrievalEvalTest 同一条底线：当前默认权重不能比纯字面差
        assertThat(currentRatio)
                .as("默认 3:1 融合的 Hit@%d 不应低于纯字面基线 %d", K, literalOnly)
                .isGreaterThanOrEqualTo(literalOnly);
    }

    /** 与 MsdsSearchService.fuse 同式，只是把权重变成参数 */
    private List<MsdsChunk> fuse(Candidates candidate, double literalWeight, double vectorWeight) {
        int rrfK = retrieval.getRrfK();
        Map<Long, Scored> fused = new HashMap<>();
        for (int rank = 0; rank < candidate.literal().size(); rank++) {
            MsdsChunk chunk = candidate.literal().get(rank);
            fused.computeIfAbsent(chunk.getId(), id -> new Scored(chunk))
                    .score += literalWeight / (rrfK + rank + 1);
        }
        for (int rank = 0; rank < candidate.vector().size(); rank++) {
            MsdsChunk chunk = candidate.vector().get(rank);
            fused.computeIfAbsent(chunk.getId(), id -> new Scored(chunk))
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

    private static boolean matches(MsdsChunk chunk, Question question) {
        return question.answers().stream()
                .anyMatch(answer -> answer.casNo().equals(chunk.getCasNo())
                        && answer.sectionNo() == chunk.getSectionNo());
    }

    private record Candidates(Question question, List<MsdsChunk> literal, List<MsdsChunk> vector) {
    }

    private static final class Scored {
        private final MsdsChunk chunk;
        private double score;

        private Scored(MsdsChunk chunk) {
            this.chunk = chunk;
        }
    }

    record EvalFile(List<Question> questions) {
    }

    record Question(String id, String type, String query, List<Answer> answers) {
    }

    record Answer(String casNo, int sectionNo) {
    }
}
