package com.lazybone.hazmat.msds;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MsdsChunkerTest {

    @Test
    void everySeedMsdsProducesTheSixteenSectionsInOrder() throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:msds/*.md");

        assertThat(resources).hasSize(18);
        for (Resource resource : resources) {
            String markdown = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            List<MsdsChunker.Chunk> chunks = MsdsChunker.split(markdown);
            // 长节会被切成多块，所以按去重后的节号断言；顺序也必须是从 1 到 16
            List<Integer> sectionNumbers = chunks.stream().map(MsdsChunker.Chunk::sectionNo).distinct().toList();

            assertThat(sectionNumbers)
                    .as("%s 的节号", resource.getFilename())
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
        }
    }

    @Test
    void chunkContentKeepsSectionHeaderSoItCanStandAlone() throws Exception {
        Resource resource = new PathMatchingResourcePatternResolver().getResource("classpath:msds/67-56-1.md");
        String markdown = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        MsdsChunker.Chunk firstAid = MsdsChunker.split(markdown).stream()
                .filter(c -> c.sectionNo() == 4)
                .findFirst()
                .orElseThrow();

        assertThat(firstAid.sectionTitle()).isEqualTo("急救措施");
        assertThat(firstAid.content()).startsWith("§4 急救措施");
    }

    @Test
    void longSectionIsSplitByParagraphAndTableStaysIntact() {
        String filler = "这是一段用于撑大章节的说明文字，目的是让单节超过四百字的上限，触发按段落切分。";
        StringBuilder markdown = new StringBuilder("## 9. 理化特性\n\n");
        for (int i = 0; i < 12; i++) {
            markdown.append("- ").append(filler).append("第").append(i).append("条。\n\n");
        }
        markdown.append("| 项目 | 数值 |\n| --- | --- |\n| 沸点 | 64.7 °C |\n| 密度 | 0.792 g/cm³ |\n");

        List<MsdsChunker.Chunk> chunks = MsdsChunker.split(markdown.toString());

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.content()).startsWith("§9 理化特性"));
        // 表格首尾行必须落在同一块里，否则单独一行「密度」就失去了表头含义
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.content()).contains("| 沸点 |");
            assertThat(chunk.content()).contains("| 密度 |");
        });
    }

    @Test
    void identicalContentProducesIdenticalHash() {
        String markdown = "## 4. 急救措施\n\n- 吸入：迅速脱离现场。\n";

        String first = MsdsChunker.split(markdown).get(0).textHash();
        String second = MsdsChunker.split(markdown).get(0).textHash();

        assertThat(first).isEqualTo(second).hasSize(64);
    }

    @Test
    void changedContentProducesDifferentHash() {
        String before = MsdsChunker.split("## 4. 急救措施\n\n- 吸入：迅速脱离现场。\n").get(0).textHash();
        String after = MsdsChunker.split("## 4. 急救措施\n\n- 吸入：迅速脱离现场并就医。\n").get(0).textHash();

        assertThat(after).isNotEqualTo(before);
    }
}
