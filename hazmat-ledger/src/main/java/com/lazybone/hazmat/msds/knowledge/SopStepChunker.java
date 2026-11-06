package com.lazybone.hazmat.msds.knowledge;

import com.lazybone.hazmat.entity.KnowledgeDocType;
import com.lazybone.hazmat.msds.MsdsChunker;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SOP / 设备手册按步骤切。
 *
 * <p>步骤是操作类文档的最小执行单位：问「泄漏时先做什么」，
 * 答案应该落在「步骤 3」而不是整章，引用也才能指到具体步骤。
 *
 * <p>只认标题形态的步骤（{@code ## 步骤 1 标题} 或 {@code ## 1. 标题}），
 * 正文里的编号列表不当作步骤，避免把一段里的枚举拆成碎块。
 */
@Component
public class SopStepChunker implements DocumentChunker {

    private static final int MAX_CHARS = 400;

    private static final Pattern STEP_HEADING = Pattern.compile(
            "^#{1,4}\\s*(?:步骤|Step)\\s*(\\d+)\\s*[：:.、\\s]\\s*(.+?)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER_HEADING =
            Pattern.compile("^#{1,4}\\s*(\\d+)[.、]\\s+(.+?)\\s*$");

    @Override
    public KnowledgeDocType docType() {
        return KnowledgeDocType.SOP;
    }

    @Override
    public List<TextChunk> split(String text, String sourceCode) {
        List<TextChunk> chunks = new ArrayList<>();
        String stepNo = null;
        String title = null;
        StringBuilder body = new StringBuilder();

        for (String line : text.split("\n", -1)) {
            Matcher matcher = STEP_HEADING.matcher(line);
            if (!matcher.matches()) {
                matcher = NUMBER_HEADING.matcher(line);
            }
            if (matcher.matches()) {
                flush(chunks, stepNo, title, body.toString());
                stepNo = matcher.group(1);
                title = matcher.group(2).trim();
                body.setLength(0);
            } else if (stepNo != null) {
                body.append(line).append('\n');
            }
        }
        flush(chunks, stepNo, title, body.toString());

        if (chunks.isEmpty()) {
            String content = text.strip();
            if (!content.isBlank()) {
                chunks.add(new TextChunk(0, sourceCode, null, 0, content,
                        MsdsChunker.sha256(content)));
            }
        }
        return chunks;
    }

    private static void flush(List<TextChunk> chunks, String stepNo, String title, String body) {
        if (stepNo == null || title == null) {
            return;
        }
        String prefix = "步骤 " + stepNo + " " + title + "\n";
        List<String> parts = ParagraphSplitter.split(body, prefix, MAX_CHARS);
        for (int i = 0; i < parts.size(); i++) {
            String content = parts.get(i);
            chunks.add(new TextChunk(0, title, stepNo, i, content,
                    MsdsChunker.sha256(content)));
        }
    }
}
