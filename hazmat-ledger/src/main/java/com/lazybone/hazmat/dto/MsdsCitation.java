package com.lazybone.hazmat.dto;

import java.util.List;

/**
 * 回答里的一个引用，编号与正文中的 {@code [n]} 对应。
 *
 * 引用由后端从检索命中生成，不让模型自由输出；模型只能决定「引用第几条」，
 * 不能编造一条不存在的出处。{@code content} 保留完整块原文，便于人工核对
 * 回答是否真的来自这里。
 */
public record MsdsCitation(
        int index,
        Long chunkId,
        String casNo,
        String chemicalName,
        int sectionNo,
        String sectionTitle,
        String content,
        double score,
        List<String> sources
) {

    public static MsdsCitation of(int index, MsdsHit hit) {
        return new MsdsCitation(
                index, hit.chunkId(), hit.casNo(), hit.chemicalName(),
                hit.sectionNo(), hit.sectionTitle(), hit.content(),
                hit.score(), hit.sources());
    }
}
