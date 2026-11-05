package com.lazybone.hazmat.msds;

import com.lazybone.hazmat.dto.MsdsAnswer;
import com.lazybone.hazmat.dto.MsdsCitation;
import com.lazybone.hazmat.dto.MsdsHit;
import com.lazybone.hazmat.msds.ai.AiUnavailableException;
import com.lazybone.hazmat.msds.ai.ChatAnswerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 生成路的三条防线：引用必须可核对、越界引用作废、模型拒答不硬凑。
 *
 * 用 mock 替代真实模型，避免测试依赖网络；检索仍用真实索引，
 * 这样引用的 chunkId 能与实际命中对上，而不是断言一段编造的元数据。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:msds-answer-gen-it;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "hazmat.ai.encode-on-startup=false"
})
class MsdsAnswerGenerationTest {

    @Autowired
    private MsdsAnswerService answerService;

    @Autowired
    private MsdsSearchService searchService;

    @MockitoBean
    private ChatAnswerClient chatClient;

    @BeforeEach
    void chatIsAvailable() {
        when(chatClient.available()).thenReturn(true);
    }

    @Test
    void generatedAnswerKeepsOnlyCitedHits() {
        List<MsdsHit> hits = searchService.search("误食 甲醇", null, 5).hits();
        assertThat(hits).hasSizeGreaterThanOrEqualTo(2);

        when(chatClient.answer(anyString(), anyList()))
                .thenReturn("应立即脱离污染区域 [2]。");

        MsdsAnswer answer = answerService.ask("误食 甲醇", null, 5);

        assertThat(answer.answerable()).isTrue();
        assertThat(answer.generated()).isTrue();
        assertThat(answer.answer()).isEqualTo("应立即脱离污染区域 [2]。");
        assertThat(answer.citations()).extracting(MsdsCitation::index).containsExactly(2);
        assertThat(answer.citations().get(0).chunkId()).isEqualTo(hits.get(1).chunkId());
    }

    @Test
    void answerWithoutAnyValidCitationFallsBackToExtraction() {
        when(chatClient.answer(anyString(), anyList()))
                .thenReturn("应立即脱离污染区域。");

        MsdsAnswer answer = answerService.ask("误食 甲醇", null, 5);

        assertThat(answer.generated()).isFalse();
        assertThat(answer.answerable()).isTrue();
        assertThat(answer.answer()).startsWith("根据 MSDS 原文");
        assertThat(answer.citations()).isNotEmpty();
        assertThat(answer.degradedReason()).contains("未包含有效引用");
    }

    @Test
    void outOfRangeCitationIsRejected() {
        when(chatClient.answer(anyString(), anyList()))
                .thenReturn("应立即脱离污染区域 [9]。");

        MsdsAnswer answer = answerService.ask("误食 甲醇", null, 5);

        assertThat(answer.generated()).isFalse();
        assertThat(answer.citations()).isNotEmpty();
        // 引用编号只能来自本次 topK 命中，不能指到列表之外
        assertThat(answer.citations()).allSatisfy(citation ->
                assertThat(citation.index()).isBetween(1, 5));
    }

    @Test
    void citationBeyondPromptWindowIsRejectedEvenIfTopKIsLarger() {
        // topK=20 时命中有 20 条，但模型只能看到前 5 条：[9] 是它无从核对的编号
        List<MsdsHit> hits = searchService.search("误食 甲醇", null, 20).hits();
        assertThat(hits).hasSizeGreaterThan(ChatAnswerClient.MAX_HITS);

        when(chatClient.answer(anyString(), anyList()))
                .thenReturn("应立即脱离污染区域 [9]。");

        MsdsAnswer answer = answerService.ask("误食 甲醇", null, 20);

        assertThat(answer.generated()).isFalse();
        assertThat(answer.degradedReason()).contains("未包含有效引用");
        assertThat(answer.citations()).allSatisfy(citation ->
                assertThat(citation.index()).isBetween(1, ChatAnswerClient.MAX_HITS));
    }

    @Test
    void modelRefusalIsReturnedAsUnanswerableInsteadOfExtractingSomething() {
        when(chatClient.answer(anyString(), anyList()))
                .thenReturn(MsdsAnswerService.NO_KNOWLEDGE);

        MsdsAnswer answer = answerService.ask("误食 甲醇", null, 5);

        assertThat(answer.answerable()).isFalse();
        assertThat(answer.generated()).isTrue();
        assertThat(answer.citations()).isEmpty();
        assertThat(answer.answer()).isEqualTo(MsdsAnswerService.NO_KNOWLEDGE);
    }

    @Test
    void paraphrasedRefusalWithIncidentalCitationsIsStillUnanswerable() {
        // 真实模型常会「拒答 + 引用相邻章节解释片段只说了什么」，而且不会逐字复述固定句。
        // 引用校验不能把这种回答误判成可答。
        when(chatClient.answer(anyString(), anyList()))
                .thenReturn("当前 MSDS 中没有找到“硫酸可作肥料使用”的相关内容，片段仅涉及危险性 [1][2]。");

        MsdsAnswer answer = answerService.ask("误食 甲醇", null, 5);

        assertThat(answer.answerable()).isFalse();
        assertThat(answer.generated()).isTrue();
        assertThat(answer.citations()).isEmpty();
        assertThat(answer.answer()).isEqualTo(MsdsAnswerService.NO_KNOWLEDGE);
    }

    @Test
    void refusalAboutMissingValueIsUnanswerable() {
        // 实测模型会说「没有找到……具体数值」「未提供……地址」，宾语不是「内容」也一样是拒答
        when(chatClient.answer(anyString(), anyList()))
                .thenReturn("当前 MSDS 中没有找到甲醇口服 LD50 的具体数值。片段仅说明经口属中等毒性 [1]。");

        MsdsAnswer answer = answerService.ask("误食 甲醇", null, 5);

        assertThat(answer.answerable()).isFalse();
        assertThat(answer.generated()).isTrue();
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    void refusalFollowedByAContrastedAnswerStaysAnswerable() {
        // 开头说「没有找到」，同一句用「但」补上答案：这是有效回答，不能误判成拒答
        when(chatClient.answer(anyString(), anyList()))
                .thenReturn("当前 MSDS 中没有找到闪点数值，但 §9 给出闭杯闪点 11 °C [1]。");

        MsdsAnswer answer = answerService.ask("误食 甲醇", null, 5);

        assertThat(answer.answerable()).isTrue();
        assertThat(answer.generated()).isTrue();
        assertThat(answer.citations()).extracting(MsdsCitation::index).containsExactly(1);
    }

    @Test
    void refusalPhraseInsideARealAnswerDoesNotDiscardIt() {
        // 回答开头是正文、末尾才补一句「其他酸类没有找到」，这是有效回答而不是拒答。
        // 拒答判定必须锚定开头，否则会把整段答案丢掉。
        when(chatClient.answer(anyString(), anyList()))
                .thenReturn("酸溅到眼睛应立即用大量清水冲洗 [1]。其他酸类在当前 MSDS 中没有找到相关内容。");

        MsdsAnswer answer = answerService.ask("误食 甲醇", null, 5);

        assertThat(answer.answerable()).isTrue();
        assertThat(answer.generated()).isTrue();
        assertThat(answer.citations()).extracting(MsdsCitation::index).containsExactly(1);
        assertThat(answer.answer()).startsWith("酸溅到眼睛应立即用大量清水冲洗");
    }

    @Test
    void modelFailureFallsBackToExtractionWithReason() {
        when(chatClient.answer(anyString(), anyList()))
                .thenThrow(new AiUnavailableException("connection refused"));

        MsdsAnswer answer = answerService.ask("误食 甲醇", null, 5);

        assertThat(answer.generated()).isFalse();
        assertThat(answer.answerable()).isTrue();
        assertThat(answer.citations()).isNotEmpty();
        assertThat(answer.degradedReason()).contains("生成模型调用失败");
    }
}
