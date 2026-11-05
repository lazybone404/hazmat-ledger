package com.lazybone.hazmat.msds;

import com.lazybone.hazmat.dto.MsdsAnswer;
import com.lazybone.hazmat.dto.MsdsCitation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /ask} 在没有生成模型时的行为：必须是「带引用的原文片段」，而不是报错或空白。
 *
 * 与检索测试一样从 classpath 的 18 份 MSDS 建索引；向量编码关闭，
 * 所以检索本身会标降级，但字面路仍应给出可引用的命中。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:msds-ask-it;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "hazmat.ai.encode-on-startup=false",
        "hazmat.ai.chat-enabled=false"
})
@AutoConfigureMockMvc
class MsdsAskTest {

    @Autowired
    private MsdsAnswerService answerService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void withoutChatModelAnswerIsExtractedFromMsdsWithCitations() {
        MsdsAnswer answer = answerService.ask("误食 甲醇", null, 5);

        assertThat(answer.answerable()).isTrue();
        assertThat(answer.generated()).isFalse();
        assertThat(answer.degraded()).isTrue();
        assertThat(answer.degradedReason()).contains("未启用生成模型");
        assertThat(answer.answer()).contains("根据 MSDS 原文").contains("[1]");
        assertThat(answer.citations()).isNotEmpty();

        MsdsCitation first = answer.citations().get(0);
        assertThat(first.index()).isEqualTo(1);
        assertThat(first.chemicalName()).isEqualTo("甲醇");
        // 「误食」经同义词扩展命中「食入」，应当摘到第 4 节急救措施
        assertThat(first.sectionNo()).isEqualTo(4);
        assertThat(answer.answer()).contains("食入");
        assertThat(first.content()).isNotBlank();
    }

    @Test
    void casNoFilterAlsoAppliesToAnswerCitations() {
        MsdsAnswer answer = answerService.ask("闪点", "67-56-1", 5);

        assertThat(answer.answerable()).isTrue();
        assertThat(answer.citations()).isNotEmpty();
        assertThat(answer.citations()).allSatisfy(citation ->
                assertThat(citation.casNo()).isEqualTo("67-56-1"));
    }

    @Test
    void answerRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/msds/ask").param("q", "甲醇 误食"))
                .andExpect(status().isUnauthorized());
    }
}
