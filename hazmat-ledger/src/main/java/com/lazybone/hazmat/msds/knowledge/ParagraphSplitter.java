package com.lazybone.hazmat.msds.knowledge;

import java.util.ArrayList;
import java.util.List;

/**
 * 单节/单条款超过字数上限时按段落再切。
 *
 * <p>与 MSDS 的切法保持同一原则：不切断段落、不拆表格；每块都带前缀，
 * 脱离原文后仍知道自己属于哪一条。
 */
final class ParagraphSplitter {

    private ParagraphSplitter() {
    }

    static List<String> split(String body, String prefix, int maxChars) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder(prefix);
        for (String paragraph : paragraphs(body)) {
            if (current.length() > prefix.length()
                    && current.length() + paragraph.length() + 1 > maxChars) {
                chunks.add(current.toString());
                current = new StringBuilder(prefix);
            }
            current.append(paragraph).append('\n');
        }
        if (current.length() > prefix.length()) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private static List<String> paragraphs(String body) {
        List<String> paragraphs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            if (line.isBlank()) {
                if (!current.isEmpty()) {
                    paragraphs.add(current.toString().strip());
                    current.setLength(0);
                }
            } else {
                current.append(line).append('\n');
            }
        }
        if (!current.isEmpty()) {
            paragraphs.add(current.toString().strip());
        }
        return paragraphs;
    }
}
