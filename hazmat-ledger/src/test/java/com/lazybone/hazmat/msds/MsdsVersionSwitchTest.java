package com.lazybone.hazmat.msds;

import com.lazybone.hazmat.entity.MsdsIndexMeta;
import com.lazybone.hazmat.repository.MsdsIndexMetaRepository;
import com.lazybone.hazmat.repository.MsdsIndexVersionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E8：重建期间前台查询必须无错误、无空结果；切换后的 active 版本是完整版本。
 *
 * <p>默认不跑（重建会反复写库，日常门禁不需要它）：
 * <pre>
 *   HAZMAT_E8=true ./mvnw -Dtest=MsdsVersionSwitchTest test
 * </pre>
 *
 * <p>这里用字面路验证「切换期间可读」：编码关闭，向量路本来就降级为空，
 * 任何一次查询都必须由字面索引给出结果。放大到 10 万块的版本不进此用例，
 * 见计划中的可选参数，避免把 CI 变成压测机。
 */
@EnabledIfEnvironmentVariable(named = "HAZMAT_E8", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:msds-e8;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
        "hazmat.ai.encode-on-startup=false"
})
class MsdsVersionSwitchTest {

    private static final Logger log = LoggerFactory.getLogger(MsdsVersionSwitchTest.class);

    private static final int REBUILDS = 3;
    private static final int SEARCHERS = 4;
    private static final List<String> QUERIES = List.of(
            "甲醇 闪点", "硫酸 稀释", "泄漏 处置", "误食 急救", "禁忌物料隔离");

    @Autowired
    private MsdsIndexService indexService;

    @Autowired
    private MsdsSearchService searchService;

    @Autowired
    private MsdsIndexMetaRepository metaRepository;

    @Autowired
    private MsdsIndexVersionRepository versionRepository;

    @Test
    void queriesStayCorrectWhileIndexIsRebuilt() throws Exception {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger emptyResults = new AtomicInteger();
        AtomicInteger searches = new AtomicInteger();
        Set<String> observedVersions = ConcurrentHashMap.newKeySet();

        ExecutorService searchers = Executors.newFixedThreadPool(SEARCHERS);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < SEARCHERS; i++) {
                int offset = i;
                futures.add(searchers.submit(() -> {
                    int round = 0;
                    while (running.get()) {
                        String query = QUERIES.get((round + offset) % QUERIES.size());
                        round++;
                        try {
                            if (searchService.search(query, null, 5).hits().isEmpty()) {
                                emptyResults.incrementAndGet();
                            }
                            searches.incrementAndGet();
                        } catch (RuntimeException e) {
                            errors.incrementAndGet();
                        }
                    }
                }));
            }

            for (int i = 0; i < REBUILDS; i++) {
                long startedAt = System.nanoTime();
                MsdsIndexService.RebuildResult result = indexService.rebuild();
                long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
                MsdsIndexMeta meta = metaRepository.findById(1).orElseThrow();
                assertThat(meta.getActiveVersion()).isEqualTo(result.version());
                observedVersions.add(meta.getActiveVersion());
                assertThat(meta.getActiveVersion()).isNotEqualTo(meta.getPreviousVersion());
                log.info("E8 重建 {}: {} 块，耗时 {} ms，active={}, previous={}",
                        i + 1, result.chunks(), elapsedMs,
                        meta.getActiveVersion(), meta.getPreviousVersion());
            }

            running.set(false);
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            running.set(false);
            searchers.shutdownNow();
            searchers.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertThat(errors.get()).as("重建期间不应有查询异常").isZero();
        assertThat(emptyResults.get()).as("重建期间字面路不应返回空结果").isZero();
        assertThat(searches.get()).as("并发查询次数太少，说明压测没跑起来").isGreaterThan(SEARCHERS);

        log.info("E8 汇总: 并发查询 {} 次，异常 {}，空结果 {}，观察到版本 {}",
                searches.get(), errors.get(), emptyResults.get(), observedVersions);

        // 每次重建都是一个完整版本；台账只保留 active + previous
        assertThat(observedVersions).hasSize(REBUILDS);
        assertThat(versionRepository.count()).isLessThanOrEqualTo(2);

        // 切换后旧版本仍可回滚，且回滚后索引仍可读
        MsdsIndexService.RollbackResult rolledBack = indexService.rollback();
        assertThat(rolledBack.activeVersion()).isNotBlank();
        assertThat(searchService.search("甲醇 闪点", null, 5).hits()).isNotEmpty();
    }
}
