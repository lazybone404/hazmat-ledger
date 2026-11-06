package com.lazybone.hazmat.msds.knowledge;

import com.lazybone.hazmat.entity.KnowledgeDocType;
import com.lazybone.hazmat.msds.MsdsChunker;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MSDS 沿用原有 16 节切法。包一层适配器而不是改 {@link MsdsChunker}：
 * 那份规则有独立单测与版本号，换接口只是为了统一索引入口。
 */
@Component
public class MsdsSectionChunker implements DocumentChunker {

    @Override
    public KnowledgeDocType docType() {
        return KnowledgeDocType.MSDS;
    }

    @Override
    public List<TextChunk> split(String text, String sourceCode) {
        return MsdsChunker.split(text).stream()
                .map(chunk -> new TextChunk(
                        chunk.sectionNo(), chunk.sectionTitle(), null,
                        chunk.partNo(), chunk.content(), chunk.textHash()))
                .toList();
    }
}
