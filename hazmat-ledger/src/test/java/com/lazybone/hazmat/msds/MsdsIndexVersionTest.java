package com.lazybone.hazmat.msds;

import com.lazybone.hazmat.common.BusinessException;
import com.lazybone.hazmat.entity.MsdsIndexMeta;
import com.lazybone.hazmat.entity.MsdsIndexVersion;
import com.lazybone.hazmat.repository.MsdsChunkRepository;
import com.lazybone.hazmat.repository.MsdsIndexMetaRepository;
import com.lazybone.hazmat.repository.MsdsIndexVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 版本化切换（D11 / E8 的常规用例）：重建产新版本、保留上一版、回滚只换指针。
 *
 * <p>{@code encode-on-startup=false} 让用例不依赖向量编码，只验证块与指针；
 * 检索走字面路即可证明「切换后仍可读」。放大并发的 E8 场景见
 * {@code tools} 与实验记录，不放进日常门禁。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:msds-index-version;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "hazmat.ai.encode-on-startup=false"
})
class MsdsIndexVersionTest {

    @Autowired
    private MsdsIndexService indexService;

    @Autowired
    private MsdsSearchService searchService;

    @Autowired
    private MsdsIndexMetaRepository metaRepository;

    @Autowired
    private MsdsIndexVersionRepository versionRepository;

    @Autowired
    private MsdsChunkRepository chunkRepository;

    @Test
    void rebuildKeepsPreviousVersionAndRollbackSwapsThePointer() {
        MsdsIndexMeta initial = metaRepository.findById(1).orElseThrow();
        String initialVersion = initial.getActiveVersion();
        assertThat(initialVersion).isNotBlank();
        assertThat(initial.getPreviousVersion()).isNull();

        // 首次版本化重建之前没有回滚目标：要明确报业务错误，而不是静默什么都不做
        assertThatThrownBy(indexService::rollback)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("回滚");

        MsdsIndexService.RebuildResult rebuilt = indexService.rebuild();
        MsdsIndexMeta afterRebuild = metaRepository.findById(1).orElseThrow();
        assertThat(afterRebuild.getActiveVersion()).isEqualTo(rebuilt.version());
        assertThat(afterRebuild.getPreviousVersion()).isEqualTo(initialVersion);
        assertThat(afterRebuild.getSwitchedAt()).isNotNull();

        // 旧版本的行还在，回滚才有东西可切；active 只包含新版本
        assertThat(chunkRepository
                .findByIndexVersionOrderByCasNoAscSectionNoAscPartNoAsc(initialVersion)).isNotEmpty();
        assertThat(indexService.activeChunks()).hasSize(rebuilt.chunks());
        assertThat(indexService.literalIndex().size()).isEqualTo(rebuilt.chunks());
        assertThat(searchService.search("甲醇 闪点", null, 5).hits()).isNotEmpty();

        Map<String, String> statuses = versionRepository.findAll().stream()
                .collect(Collectors.toMap(MsdsIndexVersion::getVersion, MsdsIndexVersion::getStatus));
        assertThat(statuses).containsEntry(rebuilt.version(), MsdsIndexVersion.ACTIVE);
        assertThat(statuses).containsEntry(initialVersion, MsdsIndexVersion.PREVIOUS);

        MsdsIndexService.RollbackResult rolledBack = indexService.rollback();
        assertThat(rolledBack.activeVersion()).isEqualTo(initialVersion);
        assertThat(rolledBack.previousVersion()).isEqualTo(rebuilt.version());

        MsdsIndexMeta afterRollback = metaRepository.findById(1).orElseThrow();
        assertThat(afterRollback.getActiveVersion()).isEqualTo(initialVersion);
        assertThat(afterRollback.getPreviousVersion()).isEqualTo(rebuilt.version());

        Map<String, String> afterStatuses = versionRepository.findAll().stream()
                .collect(Collectors.toMap(MsdsIndexVersion::getVersion, MsdsIndexVersion::getStatus));
        assertThat(afterStatuses).containsEntry(initialVersion, MsdsIndexVersion.ACTIVE);
        assertThat(afterStatuses).containsEntry(rebuilt.version(), MsdsIndexVersion.PREVIOUS);

        // 回滚后检索立刻读旧版本，块数恢复
        assertThat(indexService.literalIndex().size()).isEqualTo(rolledBack.chunks());
        assertThat(searchService.search("甲醇 闪点", null, 5).hits()).isNotEmpty();
    }

    @Test
    void refreshIfVersionChangedFollowsAnotherInstanceSwitch() {
        // 第一次重建建立可比较的基线；此时内存索引与 active 同源
        indexService.rebuild();
        assertThat(indexService.refreshIfVersionChanged()).isFalse();

        MsdsIndexMeta before = metaRepository.findById(1).orElseThrow();
        String current = before.getActiveVersion();
        String previous = before.getPreviousVersion();
        assertThat(previous).isNotNull();

        // 模拟另一个实例切了 active：只改指针，不动内存索引
        before.setActiveVersion(previous);
        before.setPreviousVersion(current);
        metaRepository.save(before);

        assertThat(indexService.refreshIfVersionChanged())
                .as("版本变化必须重建内存索引")
                .isTrue();
        assertThat(indexService.activeVersion()).isEqualTo(previous);
        assertThat(indexService.literalIndex().size())
                .isEqualTo(chunkRepository
                        .findByIndexVersionOrderByCasNoAscSectionNoAscPartNoAsc(previous).size());
        // 同一版本不会重复重建：否则每 30 秒都会扫一次全表
        assertThat(indexService.refreshIfVersionChanged()).isFalse();
    }
}
