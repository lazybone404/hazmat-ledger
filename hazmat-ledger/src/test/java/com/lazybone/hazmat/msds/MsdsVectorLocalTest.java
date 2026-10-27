package com.lazybone.hazmat.msds;

import com.lazybone.hazmat.dto.MsdsIndexStatus;
import com.lazybone.hazmat.entity.MsdsChunk;
import com.lazybone.hazmat.entity.MsdsIndexMeta;
import com.lazybone.hazmat.msds.ai.AiProperties;
import com.lazybone.hazmat.msds.ai.VectorCodec;
import com.lazybone.hazmat.repository.MsdsChunkRepository;
import com.lazybone.hazmat.repository.MsdsIndexMetaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 没有 API key 时的向量编码路径。
 *
 * 独立库名是刻意的：不同 {@code @SpringBootTest} 配置会各自启动一个上下文，
 * 共用同一个 H2 命名库时，后启动的上下文会重建表、清掉前面上下文的数据。
 * 这个类验证的是「无 key 也能把索引建起来」，不应该影响其他测试的数据。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:msds-vector-local;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "hazmat.ai.encode-on-startup=false",
        "hazmat.ai.embedding-dimensions=64"
})
class MsdsVectorLocalTest {

    @Autowired
    private MsdsVectorService vectorService;

    @Autowired
    private MsdsChunkRepository chunkRepository;

    @Autowired
    private MsdsIndexMetaRepository metaRepository;

    @Autowired
    private AiProperties properties;

    @BeforeEach
    void startFromEmptyVectors() {
        // 上一个测试可能留下了向量；每轮从「有块、无向量」开始，断言才有确定性
        List<MsdsChunk> chunks = chunkRepository.findAll();
        chunks.forEach(chunk -> {
            chunk.setVector(null);
            chunk.setIndexedAt(null);
            chunk.setEncodeFailures(0);
            chunk.setLastError(null);
        });
        chunkRepository.saveAll(chunks);
        vectorService.prepareForReindex();
    }

    @Test
    void localHashingFillsEveryChunkWithoutApiKey() {
        assertThat(properties.hasApiKey()).isFalse();

        MsdsVectorService.EncodeResult result = vectorService.encodePending(0);
        MsdsIndexStatus status = vectorService.status();

        assertThat(result.encoded()).isEqualTo(status.chunks());
        assertThat(status.encoded()).isEqualTo(status.chunks());
        assertThat(status.pending()).isZero();
        assertThat(status.failed()).isZero();
        assertThat(status.model()).isEqualTo("local-hash");
        assertThat(status.metadataReady()).isTrue();
        assertThat(status.needsRebuild()).isFalse();
        assertThat(status.encodingInProgress()).isFalse();

        MsdsChunk first = chunkRepository.findAllByOrderByCasNoAscSectionNoAscPartNoAsc().get(0);
        VectorCodec.Decoded decoded = VectorCodec.decode(first.getVector());
        assertThat(decoded).isNotNull();
        assertThat(decoded.model()).isEqualTo("local-hash");
        assertThat(decoded.vector()).hasSize(64);
    }

    @Test
    void unchangedChunksAreNotEncodedAgain() {
        vectorService.encodePending(0);

        MsdsVectorService.EncodeResult second = vectorService.encodePending(0);

        assertThat(second.attempted()).isZero();
        assertThat(second.encoded()).isZero();
    }

    @Test
    void modelChangeDisablesOldVectorsUntilExplicitReindex() {
        vectorService.encodePending(0);
        MsdsIndexMeta meta = metaRepository.findById(1).orElseThrow();
        // 模拟「上次是用另一个模型算的」：元数据被改，块里的向量仍是 local-hash
        meta.setModelId("retired-model");
        metaRepository.save(meta);

        MsdsIndexStatus stale = vectorService.status();
        assertThat(stale.needsRebuild()).isTrue();
        assertThat(stale.metadataReady()).isFalse();
        assertThat(stale.rebuildReason()).contains("模型");

        MsdsVectorService.EncodeResult blocked = vectorService.encodePending(0);
        assertThat(blocked.attempted()).isZero();
        // 停用期间旧向量原样保留，不能被悄悄覆盖成新模型的
        MsdsChunk untouched = chunkRepository.findAllByOrderByCasNoAscSectionNoAscPartNoAsc().get(0);
        assertThat(VectorCodec.decode(untouched.getVector()).model()).isEqualTo("local-hash");

        MsdsVectorService.ResetResult reset = vectorService.prepareForReindex();
        int chunkCount = Math.toIntExact(chunkRepository.count());
        assertThat(reset.vectorsInvalidated()).isTrue();
        assertThat(reset.clearedVectors()).isEqualTo(chunkCount);
        assertThat(vectorService.status().needsRebuild()).isFalse();

        MsdsVectorService.EncodeResult reencoded = vectorService.encodePending(0);
        assertThat(reencoded.encoded()).isEqualTo(chunkCount);
        assertThat(vectorService.status().encoded()).isEqualTo(chunkCount);
    }
}
