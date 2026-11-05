package com.lazybone.hazmat.msds;

import com.lazybone.hazmat.dto.MsdsAnswer;
import com.lazybone.hazmat.dto.MsdsHit;
import com.lazybone.hazmat.dto.MsdsSearchResult;
import com.lazybone.hazmat.msds.ai.ChatAnswerClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 空结果的两条不同语义：「库里没有」与「检索降级导致无法确认」。
 *
 * 这两种情况都不能编答案，但提示语必须不同；混成一句「没有找到」，
 * 用户会在向量路临时不可用时误以为知识库缺资料。
 */
class MsdsAnswerServiceTest {

    private final MsdsSearchService searchService = mock(MsdsSearchService.class);
    private final ChatAnswerClient chatClient = mock(ChatAnswerClient.class);
    private final MsdsAnswerService service = new MsdsAnswerService(searchService, chatClient);

    @Test
    void healthySearchWithNoHitsSaysNotFound() {
        when(searchService.search(anyString(), any(), anyInt()))
                .thenReturn(new MsdsSearchResult(List.of(), false, null, false, false));
        when(chatClient.available()).thenReturn(false);

        MsdsAnswer answer = service.ask("量子计算机", null, 5);

        assertThat(answer.answerable()).isFalse();
        assertThat(answer.generated()).isFalse();
        assertThat(answer.degraded()).isFalse();
        assertThat(answer.answer()).isEqualTo(MsdsAnswerService.NO_KNOWLEDGE);
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    void intentSectionWinsSentenceSelectionEvenWhenItRanksLower() {
        MsdsHit toxicology = new MsdsHit(1L, "67-56-1", "甲醇", 11, "毒理学信息",
                "[甲醇] §11 毒理学信息\n- 主要侵入途径：吸入、皮肤吸收、食入。",
                0.9, List.of("literal"));
        MsdsHit firstAid = new MsdsHit(2L, "67-56-1", "甲醇", 4, "急救措施",
                "[甲醇] §4 急救措施\n- 吸入：立即脱离污染区域。\n- 食入：立即漱口，给饮足量温水，就医。",
                0.4, List.of("literal"));
        when(searchService.search(anyString(), any(), anyInt()))
                .thenReturn(new MsdsSearchResult(List.of(toxicology, firstAid), false, null, false, false));
        when(chatClient.available()).thenReturn(false);

        MsdsAnswer answer = service.ask("误食 甲醇", null, 5);

        // 毒理块排第一，但问的是误食：急救措施应当先被选中
        assertThat(answer.citations().get(0).sectionNo()).isEqualTo(4);
        assertThat(answer.answer()).contains("食入：立即漱口");
        // 章节标题行不是可引用内容，不应出现在答案里
        assertThat(answer.answer()).doesNotContain("毒理学信息");
    }

    @Test
    void extractiveRefusesWhenNoSentenceSharesQueryTerms() {
        // 本地哈希向量对任何查询都会给出若干命中，但片段与问题毫无词面对应
        MsdsHit unrelated = new MsdsHit(1L, "7722-64-7", "高锰酸钾", 1, "化学品及企业标识",
                "[高锰酸钾] §1 化学品及企业标识\n- 中文名称：高锰酸钾\n- 英文名称：Potassium permanganate",
                0.09, List.of("vector"));
        when(searchService.search(anyString(), any(), anyInt()))
                .thenReturn(new MsdsSearchResult(List.of(unrelated), false, null, false, false));
        when(chatClient.available()).thenReturn(false);

        MsdsAnswer answer = service.ask("量子计算机怎么修", null, 5);

        assertThat(answer.answerable()).isFalse();
        assertThat(answer.generated()).isFalse();
        assertThat(answer.answer()).isEqualTo(MsdsAnswerService.NO_OVERLAP);
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    void degradedSearchWithNoHitsDoesNotClaimKnowledgeIsMissing() {
        when(searchService.search(anyString(), any(), anyInt()))
                .thenReturn(new MsdsSearchResult(
                        List.of(), true, "向量索引为空，已降级为字面检索", false, false));
        when(chatClient.available()).thenReturn(false);

        MsdsAnswer answer = service.ask("量子计算机", null, 5);

        assertThat(answer.answerable()).isFalse();
        assertThat(answer.degraded()).isTrue();
        assertThat(answer.answer()).contains("降级");
        assertThat(answer.answer()).doesNotContain("没有找到相关内容");
        assertThat(answer.degradedReason()).contains("向量索引为空");
    }
}
