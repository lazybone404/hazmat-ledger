package com.lazybone.hazmat.msds;

import com.lazybone.hazmat.common.BusinessException;
import com.lazybone.hazmat.dto.MsdsHit;
import com.lazybone.hazmat.dto.MsdsSearchResult;
import com.lazybone.hazmat.repository.MsdsChunkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 检索与增量重建的集成测试。
 *
 * 这里刻意不关种子数据以外的任何开关：MSDS 索引从 classpath 文件构建，
 * 与 chemical 表有没有数据无关，块先落库、名称后补。
 */
@SpringBootTest
class MsdsSearchTest {

    @Autowired
    private MsdsSearchService searchService;

    @Autowired
    private MsdsIndexService indexService;

    @Autowired
    private MsdsChunkRepository chunkRepository;

    @Test
    void indexCoversEverySeedFile() {
        long chunkCount = chunkRepository.count();

        assertThat(chunkCount).isGreaterThanOrEqualTo(18L * 16);
        assertThat(indexService.literalIndex().size()).isEqualTo((int) chunkCount);
    }

    @Test
    void concurrentRebuildDoesNotDuplicateChunks() throws Exception {
        long before = chunkRepository.count();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<MsdsIndexService.RebuildResult>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(indexService::rebuild));
            }
            for (Future<MsdsIndexService.RebuildResult> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 没有锁时四个事务会各自看到同一份旧快照、各插一份，块数翻倍
        assertThat(chunkRepository.count()).isEqualTo(before);
        assertThat(indexService.literalIndex().size()).isEqualTo((int) before);
    }

    @Test
    void overlongQueryIsRejected() {
        String query = "甲".repeat(201);

        assertThatThrownBy(() -> searchService.search(query, null, 5))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("200");
    }

    @Test
    void searchReportsDegradedWhenVectorIndexIsEmpty() {
        // 这个上下文关闭了启动编码，库里没有向量：字面路仍然要出结果，
        // 但必须显式告诉调用方「语义那一路没跑」，而不是静默地只返回一半
        MsdsSearchResult result = searchService.search("甲醇 闪点", null, 5);

        assertThat(result.hits()).isNotEmpty();
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradedReason()).contains("向量索引");
        assertThat(result.needsRebuild()).isFalse();
    }

    @Test
    void flashPointQuestionHitsPhysicalPropertiesSection() {
        List<MsdsHit> hits = searchService.search("甲醇 闪点", null, 5).hits();

        assertThat(hits).isNotEmpty();
        // 「甲醇」和「闪点」同时命中同一块，排序应当把它顶到第一
        assertThat(hits.get(0).casNo()).isEqualTo("67-56-1");
        assertThat(hits.get(0).sectionNo()).isEqualTo(9);
    }

    @Test
    void dilutionQuestionFindsHandlingSectionAmongTopHits() {
        List<MsdsHit> hits = searchService.search("硫酸 稀释", null, 5).hits();

        assertThat(hits).isNotEmpty();
        assertThat(hits).allMatch(hit -> hit.casNo().equals("7664-93-9"));
        // 第 2、7、13 节都提到了「稀释」：危险性概述说会放热、操作处置说酸入水、
        // 废弃处置说稀释中和。字面路分不出哪个才是用户要的，三个都召回就算达标，
        // 精确排序交给后面的向量路和 RRF 融合
        assertThat(hits).extracting(MsdsHit::sectionNo).contains(7);
    }

    @Test
    void colloquialWordForSwallowingReachesFirstAidSection() {
        // MSDS 里写的是「食入」，用户更可能说「误食」。没有同义词扩展时这一问
        // 会召回「大量误食会腹泻」的硫酸钠，而不是甲醇的急救措施
        List<MsdsHit> hits = searchService.search("误食 甲醇", null, 3).hits();

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).casNo()).isEqualTo("67-56-1");
        assertThat(hits.get(0).sectionNo()).isEqualTo(4);
    }

    @Test
    void casNoFilterLimitsResults() {
        List<MsdsHit> hits = searchService.search("闪点", "67-56-1", 10).hits();

        assertThat(hits).isNotEmpty();
        assertThat(hits).allMatch(hit -> hit.casNo().equals("67-56-1"));
    }

    @Test
    void blankQueryIsRejected() {
        assertThatThrownBy(() -> searchService.search("   ", null, 5))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void docTypeFilterSelectsKnowledgeSource() {
        // 国标样例：条款「禁忌物料隔离」是最有辨识度的一块
        List<MsdsHit> gb = searchService.searchFiltered("禁忌物料隔离", SearchFilter.of(null, "GB"), 10).hits();
        assertThat(gb).isNotEmpty();
        assertThat(gb).allMatch(hit -> "GB".equals(hit.docType()));
        assertThat(gb).anyMatch(hit -> hit.clauseNo() != null);

        // SOP 样例：步骤级切块后，引用能落到具体步骤号
        List<MsdsHit> sop = searchService.searchFiltered("泄漏 报警 疏散", SearchFilter.of(null, "SOP"), 10).hits();
        assertThat(sop).isNotEmpty();
        assertThat(sop).allMatch(hit -> "SOP".equals(hit.docType()));

        // 不加过滤时三类一起召回，说明 docType 是可选收窄而不是默认只查 MSDS
        assertThat(searchService.search("禁忌物料隔离", null, 10).hits())
                .anyMatch(hit -> "GB".equals(hit.docType()));
    }

    @Test
    void unknownDocTypeIsRejectedInsteadOfSilentlyFiltering() {
        assertThatThrownBy(() -> SearchFilter.of(null, "PDF"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rebuildReusesUnchangedChunksInsteadOfRecreatingThem() {
        MsdsIndexService.RebuildResult first = indexService.rebuild();

        // 18 份 MSDS + 1 份国标样例 + 1 份 SOP 样例；语料增减时同步改这里
        assertThat(first.files()).isEqualTo(20);
        assertThat(first.chunks()).isEqualTo(first.reused());

        MsdsIndexService.RebuildResult second = indexService.rebuild();
        assertThat(second.created()).isZero();
        assertThat(second.deleted()).isZero();
        assertThat(second.reused()).isEqualTo(second.chunks());
    }
}
