package com.lazybone.hazmat.dto;

import java.util.List;

/**
 * 一条 MSDS 检索命中。
 *
 * 带上 {@code sectionTitle} 而不是只给正文：检索结果要能直接告诉用户
 * 「这条出自《甲醇》第 4 节 急救措施」，否则几百字的正文片段看起来都差不多。
 *
 * {@code sources} 记录这一条被哪几路召回（{@code literal} / {@code vector}）。
 * 融合后分数已经是 RRF 值，单看数字无法判断「是两路都命中，还是只有一路」，
 * 排障和评测都需要这个信息。
 */
public record MsdsHit(
        Long chunkId,
        String casNo,
        String chemicalName,
        int sectionNo,
        String sectionTitle,
        String content,
        double score,
        List<String> sources
) {
}
