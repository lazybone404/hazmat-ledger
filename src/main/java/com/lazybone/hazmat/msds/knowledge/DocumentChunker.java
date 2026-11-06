package com.lazybone.hazmat.msds.knowledge;

import com.lazybone.hazmat.entity.KnowledgeDocType;

import java.util.List;

/**
 * 按文档类型切块。三类文档的结构差异是切块分化的原因：
 * MSDS 固定 16 节、国标按条款号、SOP 按步骤。
 */
public interface DocumentChunker {

    KnowledgeDocType docType();

    List<TextChunk> split(String text, String sourceCode);
}
