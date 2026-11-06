package com.lazybone.hazmat.msds.knowledge;

import com.lazybone.hazmat.entity.KnowledgeDocType;
import com.lazybone.hazmat.msds.MsdsChunker;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 国标按条款号切。
 *
 * <p>引用单位是条款而不是整节：用户要的是「依据 GB 15603 第 5.2 条」，
 * 把整章揉成一块会让回答给不出可核对的条款号。
 *
 * <p>只认 Markdown 标题里的编号（{@code ## 5.2.1 标题}）。国标原文常见的
 * 「5.2.1 标题」独立成段也兼容。识别不到任何条款时退化为整篇一块，
 * 保证内容仍可检索，而不是静默丢掉文档。
 */
@Component
public class StandardClauseChunker implements DocumentChunker {

    private static final int MAX_CHARS = 400;

    private static final Pattern HEADING =
            Pattern.compile("^#{1,4}\\s*(\\d+(?:\\.\\d+)*)\\.?\\s+(.+?)\\s*$");
    private static final Pattern BARE_CLAUSE =
            Pattern.compile("^\\s*(\\d+(?:\\.\\d+)+)\\.?\\s+(.+?)\\s*$");

    @Override
    public KnowledgeDocType docType() {
        return KnowledgeDocType.GB;
    }

    @Override
    public List<TextChunk> split(String text, String sourceCode) {
        List<TextChunk> chunks = new ArrayList<>();
        String clauseNo = null;
        String title = null;
        StringBuilder body = new StringBuilder();

        for (String line : text.split("\n", -1)) {
            Matcher matcher = HEADING.matcher(line);
            if (!matcher.matches()) {
                matcher = BARE_CLAUSE.matcher(line);
            }
            if (matcher.matches()) {
                flush(chunks, clauseNo, title, body.toString());
                clauseNo = matcher.group(1);
                title = matcher.group(2).trim();
                body.setLength(0);
            } else if (clauseNo != null) {
                // 第一个条款之前是文档头（标准号、范围说明），不进检索块
                body.append(line).append('\n');
            }
        }
        flush(chunks, clauseNo, title, body.toString());

        if (chunks.isEmpty()) {
            // 没有条款结构（例如纯文本摘录）时整篇一块，避免文档从索引里消失
            String content = text.strip();
            if (!content.isBlank()) {
                chunks.add(new TextChunk(0, sourceCode, null, 0, content,
                        MsdsChunker.sha256(content)));
            }
        }
        return chunks;
    }

    private static void flush(List<TextChunk> chunks, String clauseNo, String title, String body) {
        if (clauseNo == null || title == null) {
            return;
        }
        String prefix = "条款 " + clauseNo + " " + title + "\n";
        List<String> parts = ParagraphSplitter.split(body, prefix, MAX_CHARS);
        for (int i = 0; i < parts.size(); i++) {
            String content = parts.get(i);
            chunks.add(new TextChunk(0, title, clauseNo, i, content,
                    MsdsChunker.sha256(content)));
        }
    }
}
