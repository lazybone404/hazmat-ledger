package com.lazybone.hazmat.msds;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazybone.hazmat.msds.ai.AiProperties;
import com.lazybone.hazmat.msds.ai.HashingEmbeddingClient;
import com.lazybone.hazmat.msds.ai.HttpEmbeddingClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权重分档的纯单元测试：不启动 Spring，也不发任何网络请求。
 *
 * E4 的结论是「3:1 只对本地哈希成立，语义模型下 1:2」。这里把这两档默认值
 * 与客户端类型识别固定下来，防止以后只改模型配置、忘了改权重。
 */
class MsdsWeightProfileTest {

    @Test
    void localHashProfileStaysThreeToOne() {
        MsdsRetrievalProperties properties = new MsdsRetrievalProperties();

        assertThat(properties.literalWeightFor(false)).isEqualTo(3.0);
        assertThat(properties.vectorWeightFor(false)).isEqualTo(1.0);
    }

    @Test
    void semanticProfileDefaultsToOneToTwo() {
        MsdsRetrievalProperties properties = new MsdsRetrievalProperties();

        assertThat(properties.literalWeightFor(true)).isEqualTo(1.0);
        assertThat(properties.vectorWeightFor(true)).isEqualTo(2.0);
    }

    @Test
    void semanticWeightsCanBeOverriddenIndependently() {
        MsdsRetrievalProperties properties = new MsdsRetrievalProperties();
        properties.setSemanticLiteralWeight(2.0);
        properties.setSemanticVectorWeight(3.0);

        // 改语义档不能影响本地哈希档
        assertThat(properties.literalWeightFor(true)).isEqualTo(2.0);
        assertThat(properties.vectorWeightFor(true)).isEqualTo(3.0);
        assertThat(properties.literalWeightFor(false)).isEqualTo(3.0);
        assertThat(properties.vectorWeightFor(false)).isEqualTo(1.0);
    }

    @Test
    void serviceDistinguishesLocalHashFromSemanticClient() {
        AiProperties aiProperties = new AiProperties();
        HashingEmbeddingClient local = new HashingEmbeddingClient(aiProperties);
        HttpEmbeddingClient remote = new HttpEmbeddingClient(aiProperties, new ObjectMapper());
        MsdsVectorService service = new MsdsVectorService(null, null, aiProperties, local, remote);

        assertThat(service.usesSemanticEmbedding()).isFalse();

        aiProperties.setEnabled(true);
        aiProperties.setApiKey("test-key");
        assertThat(service.usesSemanticEmbedding()).isTrue();
    }
}
