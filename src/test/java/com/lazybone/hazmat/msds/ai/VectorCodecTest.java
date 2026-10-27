package com.lazybone.hazmat.msds.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VectorCodecTest {

    @Test
    void encodedVectorKeepsModelNameAndValues() {
        float[] vector = {0.125f, -0.5f, 1f};

        VectorCodec.Decoded decoded = VectorCodec.decode(VectorCodec.encode("text-embedding-v4", vector));

        assertThat(decoded).isNotNull();
        assertThat(decoded.model()).isEqualTo("text-embedding-v4");
        assertThat(decoded.vector()).containsExactly(0.125f, -0.5f, 1f);
    }

    @Test
    void brokenOrEmptyStorageDecodesToNull() {
        assertThat(VectorCodec.decode(null)).isNull();
        assertThat(VectorCodec.decode("   ")).isNull();
        assertThat(VectorCodec.decode("no-separator")).isNull();
        assertThat(VectorCodec.decode("model|0.1,not-a-number")).isNull();
    }

    @Test
    void nonFiniteValuesAndOversizedStorageDecodeToNull() {
        // parseFloat 能吃掉 NaN，但 NaN 参与余弦会让排序整体失去意义
        assertThat(VectorCodec.decode("model|NaN,0.5")).isNull();
        assertThat(VectorCodec.decode("model|Infinity")).isNull();
        assertThat(VectorCodec.decode("model|" + "1.0,".repeat(300_000) + "1.0")).isNull();
    }

    @Test
    void cosineRejectsDifferentDimensions() {
        assertThat(VectorCodec.cosine(new float[]{1f}, new float[]{1f, 0f})).isZero();
        assertThat(VectorCodec.cosine(new float[]{1f, 0f}, new float[]{1f, 0f})).isEqualTo(1.0);
        assertThat(VectorCodec.cosine(new float[]{1f, 0f}, new float[]{0f, 1f})).isZero();
    }
}
