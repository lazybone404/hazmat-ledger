package com.lazybone.hazmat.msds;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazybone.hazmat.dto.MsdsHit;
import com.lazybone.hazmat.entity.MsdsChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索质量评测：同一套题集分别跑字面 / 向量 / RRF 融合。
 *
 * 断言只要求「融合不差于字面基线」，不写死 Hit@5 的具体百分比——数字依赖模型、
 * 切块与题集，写死会变成一改就红的脆弱测试。真正的价值在 stdout 的逐题名次：
 * 两路分别错在哪、错的是不是同一题，直接决定要不要调权重。
 *
 * 无 key 时向量路走本地哈希，所以本测试不需要任何外部服务。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:msds-eval;DB_CLOSE_DELAY=-1",
        "hazmat.ai.encode-on-startup=false"
})
class MsdsRetrievalEvalTest {

    private static final int K = 5;

    @Autowired
    private MsdsSearchService searchService;

    @Autowired
    private MsdsIndexService indexService;

    @Autowired
    private MsdsVectorService vectorService;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void prepareVectors() {
        // 评测必须等向量就绪；surefire 全局关闭了启动编码
        vectorService.encodePending(0);
    }

    @Test
    void fusedRetrievalIsNotWorseThanLiteralBaseline() throws Exception {
        EvalFile file = loadQuestions();
        List<Question> answerable = file.questions().stream()
                .filter(question -> !question.answers().isEmpty())
                .toList();
        List<Question> traps = file.questions().stream()
                .filter(question -> question.answers().isEmpty())
                .toList();

        int literalHits = 0;
        int vectorHits = 0;
        int fusedHits = 0;
        List<String> literalMisses = new ArrayList<>();
        List<String> vectorMisses = new ArrayList<>();
        List<String> fusedMisses = new ArrayList<>();

        System.out.println("\n=== MSDS 检索评测（K=" + K + "，向量模型="
                + vectorService.activeClient().name() + "）===");
        for (Question question : answerable) {
            List<MsdsChunk> literalTop = indexService.literalIndex()
                    .search(question.query(), null, K).stream()
                    .map(MsdsLiteralIndex.Hit::chunk).toList();
            List<MsdsChunk> vectorTop = vectorService.search(question.query(), null).hits()
                    .stream().limit(K).map(MsdsVectorIndex.Hit::chunk).toList();
            List<MsdsHit> fusedTop = searchService.search(question.query(), null, K).hits();

            boolean literalHit = contains(literalTop, question);
            boolean vectorHit = contains(vectorTop, question);
            boolean fusedHit = fusedTop.stream().anyMatch(hit -> matches(hit, question));
            literalHits += literalHit ? 1 : 0;
            vectorHits += vectorHit ? 1 : 0;
            fusedHits += fusedHit ? 1 : 0;
            if (!literalHit) {
                literalMisses.add(question.id());
            }
            if (!vectorHit) {
                vectorMisses.add(question.id());
            }
            if (!fusedHit) {
                fusedMisses.add(question.id());
            }

            System.out.printf("%-3s %-8s 字面=%s 向量=%s 融合=%s | %s%n",
                    question.id(), question.type(),
                    mark(literalHit), mark(vectorHit), mark(fusedHit), question.query());
        }

        int total = answerable.size();
        System.out.printf("%nHit@%d  字面=%d/%d  向量=%d/%d  融合=%d/%d%n",
                K, literalHits, total, vectorHits, total, fusedHits, total);
        System.out.println("字面失手: " + literalMisses);
        System.out.println("向量失手: " + vectorMisses);
        System.out.println("融合失手: " + fusedMisses);
        System.out.println("两路同时失手: " + intersection(literalMisses, vectorMisses));

        System.out.println("\n陷阱题 top1（越高越说明会甩出无关内容）:");
        for (Question trap : traps) {
            double literalTop = indexService.literalIndex().search(trap.query(), null, 1).stream()
                    .mapToDouble(MsdsLiteralIndex.Hit::score).findFirst().orElse(0);
            double vectorTop = vectorService.search(trap.query(), null).hits().stream()
                    .mapToDouble(MsdsVectorIndex.Hit::score).findFirst().orElse(0);
            double fusedTop = searchService.search(trap.query(), null, 1).hits().stream()
                    .mapToDouble(MsdsHit::score).findFirst().orElse(0);
            System.out.printf("%-3s 字面=%.3f 向量=%.3f 融合=%.3f | %s%n",
                    trap.id(), literalTop, vectorTop, fusedTop, trap.query());
        }

        // 基线断言：融合不能比字面路差。若这里红了，先看失手清单是「两路同错」
        // 还是「向量路把字面正确的题挤掉了」，后者说明权重需要调。
        assertThat(fusedHits)
                .as("融合 Hit@%d 不应低于字面基线", K)
                .isGreaterThanOrEqualTo(literalHits);
    }

    private EvalFile loadQuestions() throws Exception {
        try (InputStream in = new ClassPathResource("msds-eval/questions.json").getInputStream()) {
            return objectMapper.readValue(in, EvalFile.class);
        }
    }

    private static boolean contains(List<MsdsChunk> chunks, Question question) {
        return chunks.stream().anyMatch(chunk -> question.answers().stream()
                .anyMatch(answer -> answer.casNo().equals(chunk.getCasNo())
                        && answer.sectionNo() == chunk.getSectionNo()));
    }

    private static boolean matches(MsdsHit hit, Question question) {
        return question.answers().stream()
                .anyMatch(answer -> answer.casNo().equals(hit.casNo())
                        && answer.sectionNo() == hit.sectionNo());
    }

    private static String mark(boolean hit) {
        return hit ? "✓" : "✗";
    }

    private static Set<String> intersection(List<String> a, List<String> b) {
        Set<String> right = Set.copyOf(b);
        return a.stream().filter(right::contains).collect(Collectors.toSet());
    }

    record EvalFile(List<Question> questions) {
    }

    record Question(String id, String type, String query, List<Answer> answers) {
    }

    record Answer(String casNo, int sectionNo) {
    }
}
