package com.lazybone.hazmat.msds;

import com.lazybone.hazmat.dto.MsdsSearchResult;
import com.lazybone.hazmat.msds.ai.AiUnavailableException;
import com.lazybone.hazmat.msds.ai.HttpEmbeddingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * 语义模型下的融合权重必须是 1:2，而不是本地哈希档的 3:1。
 *
 * 用 mock 让查询 embedding 失败、且库里没有已编码向量：这样只有字面路贡献分数，
 * 第一名的分数就等于「字面权重 / (rrfK + 1)」。1/(10+1) 说明走的是语义档；
 * 若代码退回 3:1，分数会是 3/(10+1)，测试立刻失败。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:msds-semantic-weight;DB_CLOSE_DELAY=-1",
        "hazmat.ai.enabled=true",
        "hazmat.ai.api-key=test-key",
        "hazmat.ai.embedding-model=fake-model",
        "hazmat.ai.embedding-dimensions=64",
        "hazmat.ai.encode-on-startup=false"
})
class MsdsSemanticWeightTest {

    private static final int RRF_K = 10;

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
        when(remoteClient.embed(anyList()))
                .thenThrow(new AiUnavailableException("测试环境不调用真实模型"));
    }

    @Test
    void semanticProfileUsesOneToTwoWeights() {
        assertThat(vectorService.usesSemanticEmbedding())
                .as("配置了 key 且开关打开时应识别为语义模型")
                .isTrue();

        MsdsSearchResult result = searchService.search("甲醇 误食", null, 5);

        assertThat(result.hits()).isNotEmpty();
        // 向量路没有候选，第一名只能是字面路第 1；分数 = 字面权重 / (rrfK + 1)
        assertThat(result.hits().get(0).score())
                .as("语义档应为 1:2；若得到 %.3f 说明还在用本地哈希的 3:1",
                        3.0 / (RRF_K + 1))
                .isCloseTo(1.0 / (RRF_K + 1), within(1e-9));
        assertThat(result.hits().get(0).sources()).containsExactly("literal");
    }
}
