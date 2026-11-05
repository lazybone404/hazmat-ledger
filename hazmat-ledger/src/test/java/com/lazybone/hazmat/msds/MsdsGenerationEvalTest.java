package com.lazybone.hazmat.msds;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazybone.hazmat.dto.MsdsAnswer;
import com.lazybone.hazmat.dto.MsdsCitation;
import com.lazybone.hazmat.dto.MsdsHit;
import com.lazybone.hazmat.dto.MsdsSearchResult;
import com.lazybone.hazmat.msds.ai.ChatAnswerClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E9：真实生成模型在题集上的端到端表现。
 *
 * 结构正确性（引用编号、引用可核对、拒答时无引用）已经在 mock 测试里锁死；
 * 这里要回答的是 mock 证明不了的两件事：
 *   1. 模型是否稳定按约定的 {@code [n]} 格式输出；
 *   2. 引用的章节是否真的对得上问题，还是只是「有编号但答偏了」。
 *
 * <b>默认不跑</b>：需要 Ollama 已启动，并拉过 chat 模型与 bge-m3。显式打开：
 * <pre>
 *   ollama serve
 *   ollama pull qwen2.5:3b
 *   ollama pull bge-m3
 *   HAZMAT_E9=true ./mvnw -Dtest=MsdsGenerationEvalTest test
 * </pre>
 * 也可指到任意 OpenAI 兼容的在线小模型。embedding 与生成可以不同源：
 * <pre>
 *   # embedding 仍走本机 Ollama bge-m3，生成走 DeepSeek
 *   HAZMAT_E9_CHAT_BASE_URL=https://api.deepseek.com/v1 \
 *   HAZMAT_E9_CHAT_API_KEY=sk-xxx \
 *   HAZMAT_E9_CHAT_MODEL=deepseek-chat \
 *   HAZMAT_E9=true ./mvnw -Dtest=MsdsGenerationEvalTest test
 * </pre>
 * 若在线模型没有 embedding 接口（DeepSeek 就没有），不要覆盖
 * {@code HAZMAT_E9_BASE_URL}，让 288 块继续用本地 bge-m3 编码。
 *
 * <b>断言只做结构性的</b>：生成质量受模型、温度、提示词影响，把 Hit 数写死会让
 * 换个模型就红。这里的数字用于人工抽检和文档记录，不当作回归闸门。
 *
 * <b>两类「不该答」分开统计</b>：题库里的 t1/t2 是安全相关但应否定（「能治头痛吗」），
 * 正确行为是有依据地否定；{@code msds-eval/e9-off-domain.json} 的 24 道是库里确实
 * 没有的（无关话题 / 库外试剂 / 库内试剂但 MSDS 不载的信息），正确行为是拒答。
 * 把两者混在一起算「拒答率」会把合理否定算成失败。
 *
 * 运行结束会打印 20 条可答题的完整回答，供人工按引用编号对照块原文核对忠实度。
 */
@EnabledIfEnvironmentVariable(named = "HAZMAT_E9", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:msds-generation-eval;DB_CLOSE_DELAY=-1",
        "hazmat.ai.encode-on-startup=false",
        "hazmat.ai.enabled=true",
        "hazmat.ai.base-url=${HAZMAT_E9_BASE_URL:http://localhost:11434/v1}",
        "hazmat.ai.api-key=${HAZMAT_E9_API_KEY:ollama}",
        "hazmat.ai.embedding-model=${HAZMAT_E9_EMBEDDING_MODEL:bge-m3}",
        "hazmat.ai.embedding-dimensions=${HAZMAT_E9_EMBEDDING_DIMENSIONS:1024}",
        "hazmat.ai.timeout-ms=120000",
        "hazmat.ai.chat-enabled=true",
        // 生成端点可以与 embedding 分开：本机 bge-m3 + 在线生成模型是常见组合
        "hazmat.ai.chat-base-url=${HAZMAT_E9_CHAT_BASE_URL:}",
        "hazmat.ai.chat-api-key=${HAZMAT_E9_CHAT_API_KEY:}",
        "hazmat.ai.chat-model=${HAZMAT_E9_CHAT_MODEL:qwen2.5:3b}",
        "hazmat.ai.chat-timeout-ms=120000",
        "hazmat.ai.chat-max-tokens=400",
        "hazmat.ai.chat-temperature=0.1"
})
class MsdsGenerationEvalTest {

    private static final int TOP_K = 5;

    @Autowired
    private MsdsAnswerService answerService;

    @Autowired
    private MsdsSearchService searchService;

    @Autowired
    private MsdsVectorService vectorService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void runsAnswerGenerationOverEvalSet() throws Exception {
        // 语义检索要等向量就绪，否则问题会被本地兜底或空向量影响
        vectorService.encodePending(0);

        EvalFile file = loadQuestions();
        List<Question> answerable = file.questions().stream()
                .filter(question -> !question.answers().isEmpty())
                .toList();
        // 两类「不该答」分开统计：
        //   题库内的 t1/t2 是安全相关但要否定（「能治头痛吗」），正确行为是有依据地否定；
        //   off-domain 是库里确实没有的（无关话题、库外试剂、库内试剂但 MSDS 不载的信息），
        //   正确行为是明确拒答。两者混在一起算「拒答率」会把合理否定算成失败。
        List<Question> traps = file.questions().stream()
                .filter(question -> question.answers().isEmpty())
                .toList();
        List<Question> offDomain = loadOffDomain().questions();

        System.out.println("\n=== E9 生成式回答（模型=" + vectorService.activeClient().name()
                + " + " + chatModel() + "，题数=" + answerable.size()
                + "，陷阱=" + traps.size() + "）===");
        System.out.println("\n| 题 | 类型 | 可答 | 生成 | 引用节 | 标准节 | 引用命中 | 答案摘要 |");
        System.out.println("|---|---|---|---|---|---|---|---|");

        int answerableCount = 0;
        int generatedCount = 0;
        int citedExpected = 0;
        int generatedWithoutCitation = 0;
        List<QuestionRun> runs = new java.util.ArrayList<>();
        List<String> refusals = new java.util.ArrayList<>();
        for (Question question : answerable) {
            Run run = run(question);
            runs.add(new QuestionRun(question, run));
            if (run.answer().answerable()) {
                answerableCount++;
            } else if (run.answer().generated()) {
                // 可答题被模型拒答：记下模型当时看得到的片段，便于判断是检索没给对还是模型太保守
                refusals.add("| " + question.id() + " | " + question.query()
                        + " | " + labels(run.visibleHits()) + " |");
            }
            if (run.answer().generated()) {
                generatedCount++;
                if (run.answer().citations().isEmpty()) {
                    generatedWithoutCitation++;
                }
            }
            if (run.citedExpectedSection()) {
                citedExpected++;
            }
            System.out.printf("| %s | %s | %s | %s | %s | %s | %s | %s |%n",
                    question.id(), question.type(),
                    run.answer().answerable() ? "是" : "否",
                    run.answer().generated() ? "是" : "否",
                    sections(run.answer().citations()),
                    expectedSections(question),
                    run.citedExpectedSection() ? "是" : "否",
                    preview(run.answer().answer()));
        }

        int trapRefused = 0;
        List<String> trapAnswered = new java.util.ArrayList<>();
        for (Question trap : traps) {
            Run run = run(trap);
            if (!run.answer().answerable()) {
                trapRefused++;
            } else {
                trapAnswered.add("| " + trap.id() + " | " + trap.query() + " | "
                        + preview(run.answer().answer()) + " |");
            }
            System.out.printf("| %s | 陷阱 | %s | %s | %s | - | - | %s |%n",
                    trap.id(),
                    run.answer().answerable() ? "是" : "否",
                    run.answer().generated() ? "是" : "否",
                    sections(run.answer().citations()),
                    preview(run.answer().answer()));
        }

        // 库外问题：只统计「有没有明确说没有」。答了的要逐条看，不能一律算编造——
        // 模型可能给出「不能/库里没有，建议查官方 SDS」这种有依据的否定。
        java.util.Map<String, int[]> offStats = new java.util.LinkedHashMap<>();
        List<String> offAnswered = new java.util.ArrayList<>();
        for (Question question : offDomain) {
            Run run = run(question);
            String group = question.id().substring(0, 1);
            int[] stat = offStats.computeIfAbsent(group, key -> new int[2]);
            stat[1]++;
            if (!run.answer().answerable()) {
                stat[0]++;
            } else {
                offAnswered.add("| " + question.id() + " | " + question.query() + " | "
                        + sections(run.answer().citations()) + " | "
                        + preview(run.answer().answer()) + " |");
            }
            System.out.printf("| %s | 库外 | %s | %s | %s | - | - | %s |%n",
                    question.id(),
                    run.answer().answerable() ? "是" : "否",
                    run.answer().generated() ? "是" : "否",
                    sections(run.answer().citations()),
                    preview(run.answer().answer()));
        }

        System.out.printf("%n汇总：可答 %d/%d，生成 %d/%d，引用命中标准节 %d/%d，"
                        + "陷阱有依据否定 %d/%d，生成但无引用 %d%n",
                answerableCount, answerable.size(),
                generatedCount, answerable.size(),
                citedExpected, answerable.size(),
                traps.size() - trapRefused, traps.size(),
                generatedWithoutCitation);
        int offTotal = offDomain.size();
        int offRefused = offStats.values().stream().mapToInt(stat -> stat[0]).sum();
        System.out.printf("库外问题明确拒答 %d/%d（无关话题 %d/%d，库外试剂 %d/%d，库内试剂库外信息 %d/%d）%n",
                offRefused, offTotal,
                offStats.getOrDefault("o", new int[2])[0], offStats.getOrDefault("o", new int[2])[1],
                offStats.getOrDefault("r", new int[2])[0], offStats.getOrDefault("r", new int[2])[1],
                offStats.getOrDefault("g", new int[2])[0], offStats.getOrDefault("g", new int[2])[1]);

        if (!trapAnswered.isEmpty()) {
            System.out.println("\n陷阱题给出了回答（需人工判断是有依据否定还是编造）：");
            System.out.println("| 题 | 问题 | 答案摘要 |");
            System.out.println("|---|---|---|");
            trapAnswered.forEach(System.out::println);
        }
        if (!offAnswered.isEmpty()) {
            System.out.println("\n库外问题被当作可答（需人工判断）：");
            System.out.println("| 题 | 问题 | 引用节 | 答案摘要 |");
            System.out.println("|---|---|---|---|");
            offAnswered.forEach(System.out::println);
        }

        // 完整回答：忠实度只能拿全文与块原文逐句比对，摘要不够
        System.out.println("\n=== 可答题完整回答（人工核对内容忠实度用）===");
        for (QuestionRun entry : runs) {
            System.out.println("\n[" + entry.question().id() + "] " + entry.question().query());
            System.out.println(entry.run().answer().answer());
            System.out.println("引用：" + sections(entry.run().answer().citations()));
            if (!entry.run().answer().generated()) {
                // 降级原因能区分「模型没给合法引用」与「调用失败」，是排查生成波动的唯一线索
                System.out.println("降级原因：" + entry.run().answer().degradedReason());
            }
        }

        if (!refusals.isEmpty()) {
            System.out.println("\n可答题被模型拒答（可见片段）：");
            System.out.println("| 题 | 问题 | 模型当时可见的片段 |");
            System.out.println("|---|---|---|");
            refusals.forEach(System.out::println);
        }

        // 结构不变量：无论模型怎么答，接口都不能返回无引用或引用越界的生成结果。
        // 模型对可答题拒答是合法结果（answerable=false），不能当成结构错误；
        // 这里只要求拒答时不带引用。
        for (QuestionRun entry : runs) {
            Run run = entry.run();
            if (!run.answer().generated()) {
                continue;
            }
            if (!run.answer().answerable()) {
                assertThat(run.answer().citations())
                        .as("%s 的拒答不应带引用", entry.question().id())
                        .isEmpty();
                continue;
            }
            assertThat(run.answer().citations())
                    .as("%s 的生成回答必须带引用", entry.question().id())
                    .isNotEmpty();
            assertThat(run.answer().citations()).allSatisfy(citation -> {
                assertThat(citation.index())
                        .as("%s 的引用编号必须在模型可见范围内", entry.question().id())
                        .isBetween(1, ChatAnswerClient.MAX_HITS);
                assertThat(run.visibleHits())
                        .as("%s 的引用必须来自本次喂给模型的片段", entry.question().id())
                        .extracting(MsdsHit::chunkId)
                        .contains(citation.chunkId());
            });
        }
    }

    private Run run(Question question) {
        MsdsSearchResult search = searchService.search(question.query(), null, TOP_K);
        MsdsAnswer answer = answerService.ask(question.query(), null, TOP_K);

        List<MsdsHit> visible = search.hits().stream()
                .limit(ChatAnswerClient.MAX_HITS)
                .toList();

        boolean citedExpected = answer.citations().stream().anyMatch(citation ->
                question.answers().stream().anyMatch(expected ->
                        expected.casNo().equals(citation.casNo())
                                && expected.sectionNo() == citation.sectionNo()));
        return new Run(answer, visible, citedExpected);
    }

    private static String labels(List<MsdsHit> hits) {
        if (hits.isEmpty()) {
            return "-";
        }
        return hits.stream()
                .map(hit -> hit.casNo() + " §" + hit.sectionNo())
                .reduce((a, b) -> a + ", " + b)
                .orElse("-");
    }

    private static String sections(List<MsdsCitation> citations) {
        if (citations.isEmpty()) {
            return "-";
        }
        return citations.stream()
                .map(citation -> citation.casNo() + " §" + citation.sectionNo())
                .reduce((a, b) -> a + ", " + b)
                .orElse("-");
    }

    private static String expectedSections(Question question) {
        return question.answers().stream()
                .map(answer -> answer.casNo() + " §" + answer.sectionNo())
                .reduce((a, b) -> a + ", " + b)
                .orElse("-");
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "…";
    }

    private String chatModel() {
        return System.getenv().getOrDefault("HAZMAT_E9_CHAT_MODEL", "qwen2.5:3b");
    }

    private EvalFile loadQuestions() throws Exception {
        try (InputStream in = new ClassPathResource("msds-eval/questions.json").getInputStream()) {
            return objectMapper.readValue(in, EvalFile.class);
        }
    }

    /**
     * 库外问题集：题库之外、本库 MSDS 确实回答不了的问法。
     * 单独一个文件，避免污染 20 道可答题的检索评测。
     */
    private EvalFile loadOffDomain() throws Exception {
        ClassPathResource resource = new ClassPathResource("msds-eval/e9-off-domain.json");
        if (!resource.exists()) {
            return new EvalFile(List.of());
        }
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readValue(in, EvalFile.class);
        }
    }

    private record Run(MsdsAnswer answer, List<MsdsHit> visibleHits, boolean citedExpectedSection) {
    }

    private record QuestionRun(Question question, Run run) {
    }

    record EvalFile(List<Question> questions) {
    }

    record Question(String id, String type, String query, List<Answer> answers) {
    }

    record Answer(String casNo, int sectionNo) {
    }
}
