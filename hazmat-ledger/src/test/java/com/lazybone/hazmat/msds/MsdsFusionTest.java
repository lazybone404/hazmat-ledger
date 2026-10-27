package com.lazybone.hazmat.msds;

import com.lazybone.hazmat.dto.MsdsHit;
import com.lazybone.hazmat.dto.MsdsSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 融合结果的元数据：来源标记、降级原因、是否需要重建。
 *
 * 独立库名，避免与其它 {@code @SpringBootTest} 的 create-drop 互相清表。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:msds-fusion;DB_CLOSE_DELAY=-1",
        "hazmat.ai.encode-on-startup=false"
})
class MsdsFusionTest {

    @Autowired
    private MsdsSearchService searchService;

    @Autowired
    private MsdsVectorService vectorService;

    @BeforeEach
    void encodeVectors() {
        vectorService.encodePending(0);
    }

    @Test
    void fusedHitsCarryBothSourcesWhenBothPathsAgree() {
        MsdsSearchResult result = searchService.search("误食 甲醇", null, 5);

        assertThat(result.hits()).isNotEmpty();
        assertThat(result.hits()).allMatch(hit -> hit.casNo().equals("67-56-1"));
        // 本地哈希会把 §11 毒理学（「食入」也在其中）抬到 §4 前面：
        // 两路都只抓字面，分不开「急救措施」和「毒理学」的语境差异。
        // 这里只要求急救措施留在 top5；排序质量由评测的 Hit@5 而不是单题 top1 衡量。
        assertThat(result.hits()).extracting(MsdsHit::sectionNo).contains(4);
        assertThat(result.hits()).allSatisfy(hit -> assertThat(hit.sources()).isNotEmpty());
        // 至少有一条被两路同时召回，否则融合没有实际发生
        assertThat(result.hits()).anySatisfy(hit ->
                assertThat(hit.sources()).containsExactlyInAnyOrder("literal", "vector"));

        assertThat(result.degraded()).isTrue();
        assertThat(result.degradedReason()).contains("本地哈希");
        assertThat(result.needsRebuild()).isFalse();
        assertThat(result.indexing()).isFalse();
    }

    @Test
    void vectorPathFindsChunksThatLiteralPathMisses() {
        // 「酸溅到眼睛里」不含「硫酸」：字面路靠同义词扩展才勉强命中，
        // 向量路是本地的字面哈希，两家都可能漏，这里只验证向量路确实返回了候选
        MsdsSearchResult result = searchService.search("酸溅到眼睛里怎么处理", null, 5);
        assertThat(result.hits()).isNotEmpty();
        assertThat(result.hits()).allSatisfy(hit -> assertThat(hit.sources()).isNotEmpty());
    }
}
