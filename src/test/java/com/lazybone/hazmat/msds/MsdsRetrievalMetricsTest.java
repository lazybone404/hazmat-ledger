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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索指标评测：Hit@1 / Hit@5 / MRR@10 / Recall@5，按题集类型分组。
 *
 * 与 {@link MsdsRetrievalEvalTest} 的分工：那边只回答「融合有没有比字面差」，
 * 是回归闸门；这里回答「到底有多好」，产出可以贴进文档和面试的数字。
 * 两者共用同一套题集与同一套匹配规则，所以 Hit@5 必须对得上。
 *
 * <b>匹配粒度的已知偏差：</b>题集的标注只到「哪份试剂的第几节」，
 * 同一切块版本下同一节可能被切成多块（正文超过 400 字时按段落二次切）。
 * 命中任一块都算对，会让指标略偏乐观。要收紧就得在题集里补 partNo 或关键句，
 * 那是下一轮标注的事，不在本测试里偷偷改口径——否则新旧数字无法对比。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:msds-metrics;DB_CLOSE_DELAY=-1",
        "hazmat.ai.encode-on-startup=false"
})
class MsdsRetrievalMetricsTest {

    private static final int K = 5;
    /** MRR 的截断深度：排在 10 名之外视为没召回，倒数贡献为 0 */
    private static final int MRR_DEPTH = 10;

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
    void reportsHitRateAndRankingMetrics() throws Exception {
        EvalFile file = loadQuestions();
        List<Question> answerable = file.questions().stream()
                .filter(question -> !question.answers().isEmpty())
                .toList();
        List<Question> traps = file.questions().stream()
                .filter(question -> question.answers().isEmpty())
                .toList();

        System.out.println("\n=== MSDS 检索指标（Hit@1 / Hit@5 / MRR@10 / Recall@5，模型="
                + vectorService.activeClient().name() + "）===");

        Metrics literal = measure(answerable, this::literalTop);
        Metrics vector = measure(answerable, this::vectorTop);
        Metrics fused = measure(answerable, this::fusedTop);

        printTable("全部可答题（n=" + answerable.size() + "）",
                paths(literal, vector, fused));

        Map<String, List<Question>> byType = answerable.stream()
                .collect(Collectors.groupingBy(Question::type, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<String, List<Question>> entry : byType.entrySet()) {
            List<Question> group = entry.getValue();
            printTable("类型 " + entry.getKey() + "（n=" + group.size() + "）",
                    paths(measure(group, this::literalTop),
                            measure(group, this::vectorTop),
                            measure(group, this::fusedTop)));
        }

        System.out.println("\n陷阱题 top1 分数（越高越说明会甩出无关内容）:");
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

        // 回归闸门与旧评测保持一致：融合不能比字面差。
        // 其余指标只打印不断言——它们的值依赖模型与题集，写死会变成一改就红。
        assertThat(fused.hitAt5())
                .as("融合 Hit@5 不应低于字面基线")
                .isGreaterThanOrEqualTo(literal.hitAt5());
    }

    private static Map<String, Metrics> paths(Metrics literal, Metrics vector, Metrics fused) {
        Map<String, Metrics> byPath = new LinkedHashMap<>();
        byPath.put("字面", literal);
        byPath.put("向量", vector);
        byPath.put("融合", fused);
        return byPath;
    }

    private List<Key> literalTop(Question question) {
        return indexService.literalIndex().search(question.query(), null, MRR_DEPTH).stream()
                .map(hit -> Key.of(hit.chunk()))
                .toList();
    }

    private List<Key> vectorTop(Question question) {
        return vectorService.search(question.query(), null).hits().stream()
                .limit(MRR_DEPTH)
                .map(hit -> Key.of(hit.chunk()))
                .toList();
    }

    private List<Key> fusedTop(Question question) {
        return searchService.search(question.query(), null, MRR_DEPTH).hits().stream()
                .map(hit -> new Key(hit.casNo(), hit.sectionNo()))
                .toList();
    }

    private static Metrics measure(List<Question> questions,
                                   Function<Question, List<Key>> retriever) {
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

    /** 第一条命中的名次（1 开始）；没有命中返回 0 */
    private static int firstMatchRank(List<Key> top, Question question) {
        for (int rank = 0; rank < Math.min(top.size(), MRR_DEPTH); rank++) {
            if (matches(top.get(rank), question)) {
                return rank + 1;
            }
        }
        return 0;
    }

    /** 前 k 条里覆盖了多少个标准答案；一题有多个答案时才有别于 Hit@k */
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

    private static void printTable(String title, Map<String, Metrics> byPath) {
        System.out.println("\n" + title);
        System.out.println("| 路径 | Hit@1 | Hit@5 | MRR@10 | Recall@5 |");
        System.out.println("|---|---:|---:|---:|---:|");
        byPath.forEach((name, metrics) -> System.out.printf("| %s | %s | %s | %.3f | %.3f |%n",
                name,
                ratio(metrics.hitAt1(), metrics.total()),
                ratio(metrics.hitAt5(), metrics.total()),
                metrics.mrr(),
                metrics.recallAt5()));
    }

    private static String ratio(int hit, int total) {
        return total == 0 ? "0/0" : String.format("%d/%d (%.1f%%)", hit, total, 100.0 * hit / total);
    }

    private EvalFile loadQuestions() throws Exception {
        try (InputStream in = new ClassPathResource("msds-eval/questions.json").getInputStream()) {
            return objectMapper.readValue(in, EvalFile.class);
        }
    }

    /** 匹配只看「哪份试剂的第几节」，与旧评测保持一致，便于横向对比 */
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
