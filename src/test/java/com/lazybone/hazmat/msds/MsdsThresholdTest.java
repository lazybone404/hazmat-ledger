package com.lazybone.hazmat.msds;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 向量阈值标定：打印相关查询与陷阱查询的 top1 余弦分布，供人工选点。
 *
 * 这是标定工具，不是性能门禁——数字依赖模型与题集，机器不同也会微变。
 * 换 embedding 模型、改切块规则或改题集后都要重新跑，把选好的值写进
 * {@code hazmat.ai.min-score}；不要把旧模型的阈值照搬过来。
 *
 * 本轮的本地哈希阈值保持 0.0：它的相关分与无关分有重叠，硬卡一个数只会
 * 把本来能召回的结果筛掉。等云端模型接入后，这张表才有明确的取舍点。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:msds-threshold;DB_CLOSE_DELAY=-1",
        "hazmat.ai.encode-on-startup=false"
})
class MsdsThresholdTest {

    @Autowired
    private MsdsVectorService vectorService;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void encodeVectors() {
        vectorService.encodePending(0);
    }

    @Test
    void printsTop1ScoresForRelevantAndTrapQuestions() throws Exception {
        EvalFile file = loadQuestions();

        System.out.println("\n=== 向量 top1 余弦分布（模型="
                + vectorService.activeClient().name() + "，min-score="
                + vectorService.activeClient().minScore() + "）===");
        System.out.println("相关题（期望答案是否在 top1 不参与判分，只看分数区间）:");
        int sampled = 0;
        for (Question question : file.questions()) {
            if (question.answers().isEmpty()) {
                continue;
            }
            double top1 = vectorService.search(question.query(), null).hits().stream()
                    .mapToDouble(hit -> hit.score()).findFirst().orElse(0);
            System.out.printf("%-3s %.3f | %s%n", question.id(), top1, question.query());
            sampled++;
        }

        System.out.println("陷阱题:");
        for (Question question : file.questions()) {
            if (!question.answers().isEmpty()) {
                continue;
            }
            double top1 = vectorService.search(question.query(), null).hits().stream()
                    .mapToDouble(hit -> hit.score()).findFirst().orElse(0);
            System.out.printf("%-3s %.3f | %s%n", question.id(), top1, question.query());
        }

        // 只保证「向量路确实跑了」；阈值选点由人看上面的表决定
        assertThat(sampled).isPositive();
    }

    private EvalFile loadQuestions() throws Exception {
        try (InputStream in = new ClassPathResource("msds-eval/questions.json").getInputStream()) {
            return objectMapper.readValue(in, EvalFile.class);
        }
    }

    record EvalFile(List<Question> questions) {
    }

    record Question(String id, String type, String query, List<Answer> answers) {
    }

    record Answer(String casNo, int sectionNo) {
    }
}
