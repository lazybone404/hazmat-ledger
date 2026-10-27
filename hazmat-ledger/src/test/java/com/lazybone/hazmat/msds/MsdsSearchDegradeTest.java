package com.lazybone.hazmat.msds;

import com.lazybone.hazmat.dto.MsdsSearchResult;
import com.lazybone.hazmat.msds.ai.AiUnavailableException;
import com.lazybone.hazmat.msds.ai.HttpEmbeddingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * 查询向量算不出来时，检索仍要返回字面结果，并显式标记降级。
 *
 * 用 mock 模拟「索引已建好、但查询这一刻模型挂了」：块向量是现成的，
 * 失败只发生在 query embedding 这一步。这是云端模型最现实的故障形态。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:msds-search-degrade;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "hazmat.ai.enabled=true",
        "hazmat.ai.api-key=test-key",
        "hazmat.ai.embedding-model=fake-model",
        "hazmat.ai.embedding-dimensions=64",
        "hazmat.ai.batch-size=3",
        "hazmat.ai.encode-on-startup=false"
})
class MsdsSearchDegradeTest {

    @Autowired
    private MsdsSearchService searchService;

    @Autowired
    private MsdsVectorService vectorService;

    @MockitoBean
    private HttpEmbeddingClient remoteClient;

    @BeforeEach
    void stubRemoteModel() {
        when(remoteClient.name()).thenReturn("fake-model");
        when(remoteClient.dimensions()).thenReturn(64);
        when(remoteClient.minScore()).thenReturn(0.0);
        when(remoteClient.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            if (texts.size() == 1) {
                // 单条 = 查询侧调用；编码侧是成批的
                throw new AiUnavailableException("query embedding down");
            }
            float[] vector = new float[64];
            vector[0] = 1f;
            return texts.stream().map(text -> vector).toList();
        });

        vectorService.prepareForReindex();
        vectorService.encodePending(0);
    }

    @Test
    void searchFallsBackToLiteralWhenQueryEmbeddingFails() {
        MsdsSearchResult result = searchService.search("甲醇 闪点", null, 5);

        assertThat(result.hits()).isNotEmpty();
        assertThat(result.hits().get(0).casNo()).isEqualTo("67-56-1");
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradedReason()).contains("语义检索暂不可用");
        assertThat(result.hits()).allSatisfy(hit ->
                assertThat(hit.sources()).containsExactly("literal"));
        assertThat(result.needsRebuild()).isFalse();
    }
}
