package com.lazybone.hazmat.msds.knowledge;

import com.lazybone.hazmat.entity.KnowledgeDocType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 按文档类型选切块器。新增类型时只需实现 {@link DocumentChunker} 并注册为 Bean。 */
@Component
public class ChunkerRegistry {

    private final Map<KnowledgeDocType, DocumentChunker> byType = new EnumMap<>(KnowledgeDocType.class);

    public ChunkerRegistry(List<DocumentChunker> chunkers) {
        for (DocumentChunker chunker : chunkers) {
            byType.put(chunker.docType(), chunker);
        }
    }

    public DocumentChunker of(KnowledgeDocType type) {
        DocumentChunker chunker = byType.get(type);
        if (chunker == null) {
            throw new IllegalStateException("没有为 " + type + " 注册切块器");
        }
        return chunker;
    }
}
