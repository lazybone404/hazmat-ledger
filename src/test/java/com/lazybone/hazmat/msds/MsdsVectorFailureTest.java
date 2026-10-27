package com.lazybone.hazmat.msds;

import com.lazybone.hazmat.dto.MsdsIndexStatus;
import com.lazybone.hazmat.entity.MsdsChunk;
import com.lazybone.hazmat.msds.ai.AiProperties;
import com.lazybone.hazmat.msds.ai.AiUnavailableException;
import com.lazybone.hazmat.msds.ai.HttpEmbeddingClient;
import com.lazybone.hazmat.repository.MsdsChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 云端模型不可用时的行为：暂停、持久化、不无限重试。
 *
 * 用 mock 替换 HTTP 客户端，测试不依赖网络与真实 key。真实 key 只在人工验证时用——
 * 常规测试打真实模型既不稳定，也会把正文发到外部服务。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:msds-vector-failure;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "hazmat.ai.enabled=true",
        "hazmat.ai.api-key=test-key",
        "hazmat.ai.embedding-model=fake-model",
        "hazmat.ai.embedding-dimensions=64",
        "hazmat.ai.batch-size=3",
        "hazmat.ai.encode-on-startup=false",
        "hazmat.ai.max-consecutive-failures=3",
        "hazmat.ai.max-failures-per-chunk=3"
})
class MsdsVectorFailureTest {

    @Autowired
    private MsdsVectorService vectorService;

    @Autowired
    private MsdsChunkRepository chunkRepository;

    @Autowired
    private AiProperties properties;

    @MockitoBean
    private HttpEmbeddingClient remoteClient;

    @BeforeEach
    void useFakeRemoteModel() {
        when(remoteClient.name()).thenReturn("fake-model");
        when(remoteClient.dimensions()).thenReturn(64);
        // 每个方法从「有块、无向量、无暂停」开始
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
    void consecutiveFailuresPauseInsteadOfRetryingForever() {
        when(remoteClient.embed(anyList()))
                .thenThrow(new AiUnavailableException("密钥无效"));

        MsdsVectorService.EncodeResult result = vectorService.encodePending(3);

        assertThat(result.failed()).isEqualTo(3);
        assertThat(result.paused()).isTrue();
        MsdsIndexStatus status = vectorService.status();
        assertThat(status.paused()).isTrue();
        assertThat(status.pausedReason()).contains("连续 3 块");
        assertThat(status.pausedAt()).isNotNull();

        // 暂停是持久的：再调一次不应再打模型
        MsdsVectorService.EncodeResult second = vectorService.encodePending(3);
        assertThat(second.attempted()).isZero();
        assertThat(second.paused()).isTrue();
        verify(remoteClient, times(1)).embed(anyList());
    }

    @Test
    void explicitReindexClearsPauseAndRetries() {
        when(remoteClient.embed(anyList()))
                .thenThrow(new AiUnavailableException("临时故障"));
        vectorService.encodePending(3);
        assertThat(vectorService.status().paused()).isTrue();

        MsdsVectorService.ResetResult reset = vectorService.prepareForReindex();
        assertThat(reset.model()).isEqualTo("fake-model");
        assertThat(vectorService.status().paused()).isFalse();

        doAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(text -> new float[64]).toList();
        }).when(remoteClient).embed(anyList());

        vectorService.encodePending(0);

        MsdsIndexStatus status = vectorService.status();
        assertThat(status.encoded()).isEqualTo(status.chunks());
        assertThat(status.pending()).isZero();
        assertThat(status.failed()).isZero();
        assertThat(status.paused()).isFalse();
    }

    @Test
    void chunkStopsRetryingAfterPerChunkFailureLimit() {
        // 关掉「本轮暂停」，单独验证单块上限：两块都失败到上限，状态里应记为已放弃
        int previousPauseThreshold = properties.getMaxConsecutiveFailures();
        properties.setMaxConsecutiveFailures(1000);
        try {
            when(remoteClient.embed(anyList()))
                    .thenThrow(new AiUnavailableException("持续失败"));

            for (int i = 0; i < 3; i++) {
                vectorService.encodePending(1);
            }
            MsdsChunk first = orderedChunks().get(0);
            assertThat(first.getEncodeFailures()).isEqualTo(3);
            assertThat(first.getLastError()).contains("持续失败");
            assertThat(vectorService.status().failed()).isEqualTo(1);

            // 达到上限的块被跳过，下一块开始失败，而不是在同一块上无限重试
            MsdsVectorService.EncodeResult next = vectorService.encodePending(1);
            assertThat(next.attempted()).isEqualTo(1);
            assertThat(orderedChunks().get(0).getEncodeFailures()).isEqualTo(3);
            assertThat(vectorService.status().failed()).isEqualTo(1);
        } finally {
            properties.setMaxConsecutiveFailures(previousPauseThreshold);
        }
    }

    private List<MsdsChunk> orderedChunks() {
        return chunkRepository.findAllByOrderByCasNoAscSectionNoAscPartNoAsc();
    }
}
