package com.lazybone.hazmat.msds;

import com.lazybone.hazmat.entity.MsdsChunk;
import com.lazybone.hazmat.entity.MsdsIndexVersion;
import com.lazybone.hazmat.msds.ai.AiProperties;
import com.lazybone.hazmat.msds.knowledge.ChunkerRegistry;
import com.lazybone.hazmat.repository.ChemicalRepository;
import com.lazybone.hazmat.repository.KnowledgeDocumentRepository;
import com.lazybone.hazmat.repository.MsdsChunkRepository;
import com.lazybone.hazmat.repository.MsdsIndexLockRepository;
import com.lazybone.hazmat.repository.MsdsIndexMetaRepository;
import com.lazybone.hazmat.repository.MsdsIndexVersionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两个实例并发重建：数据库行锁保证它们串行，不会各写一份、互相覆盖。
 *
 * <p>为什么手工 new 第二个 {@link MsdsIndexService}：进程内的 {@code ReentrantLock}
 * 会先把同一实例的请求串行化，用它测出来的「串行」是本地锁的功劳，证明不了行锁。
 * 两个实例各有各的本地锁，能在同一时刻真正进入事务，此时只有数据库行锁能挡住
 * 第二个事务——这正是多实例部署里的真实形态。
 *
 * <p>{@code LOCK_TIMEOUT=10000}：这里要验证「排队后成功」，超时调得太短会把正常等待
 * 误判成冲突。抢锁失败的路径由 {@code MsdsIndexLockConflictTest} 用短超时单独验证。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:msds-index-lock;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "hazmat.ai.encode-on-startup=false",
        "hazmat.jobs.enabled=false"
})
class MsdsIndexLockTest {

    @Autowired private MsdsIndexService indexService;
    @Autowired private MsdsChunkRepository chunkRepository;
    @Autowired private ChemicalRepository chemicalRepository;
    @Autowired private KnowledgeDocumentRepository documentRepository;
    @Autowired private MsdsIndexMetaRepository metaRepository;
    @Autowired private MsdsIndexVersionRepository versionRepository;
    @Autowired private MsdsIndexLockRepository lockRepository;
    @Autowired private ChunkerRegistry chunkers;
    @Autowired private AiProperties aiProperties;
    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("两个实例同时重建：串行执行，最终只有一个 active 版本且块不重复")
    void concurrentRebuildFromTwoInstancesSerializes() throws Exception {
        MsdsIndexService secondInstance = new MsdsIndexService(chunkRepository, chemicalRepository,
                documentRepository, metaRepository, versionRepository, lockRepository,
                chunkers, aiProperties, transactionTemplate);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<MsdsIndexService.RebuildResult> first = pool.submit(() -> {
                start.await();
                return indexService.rebuild();
            });
            Future<MsdsIndexService.RebuildResult> second = pool.submit(() -> {
                start.await();
                return secondInstance.rebuild();
            });
            start.countDown();

            MsdsIndexService.RebuildResult a = first.get(60, TimeUnit.SECONDS);
            MsdsIndexService.RebuildResult b = second.get(60, TimeUnit.SECONDS);

            assertThat(a.chunks()).isEqualTo(b.chunks());
            assertThat(a.version()).isNotEqualTo(b.version());

            String active = indexService.activeVersion();
            assertThat(active).isNotNull();
            assertThat(chunkRepository
                    .findByIndexVersionOrderByCasNoAscSectionNoAscPartNoAsc(active))
                    .hasSize(a.chunks());

            // 同一版本内不允许同一段正文出现两次：并发重建若没串行，最容易表现为这里翻倍
            List<MsdsChunk> activeChunks = chunkRepository
                    .findByIndexVersionOrderByCasNoAscSectionNoAscPartNoAsc(active);
            Set<String> keys = new HashSet<>();
            for (MsdsChunk chunk : activeChunks) {
                assertThat(keys.add(chunk.getCasNo() + "|" + chunk.getSectionNo() + "|"
                        + chunk.getPartNo() + "|" + chunk.getTextHash()))
                        .as("块重复: %s 第 %s 节第 %s 段", chunk.getCasNo(),
                                chunk.getSectionNo(), chunk.getPartNo())
                        .isTrue();
            }

            assertThat(versionRepository.findAll()).filteredOn(
                    version -> MsdsIndexVersion.ACTIVE.equals(version.getStatus())).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
