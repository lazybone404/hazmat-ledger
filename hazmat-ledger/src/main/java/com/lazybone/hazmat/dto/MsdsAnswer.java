package com.lazybone.hazmat.dto;

import java.util.List;

/**
 * {@code /api/msds/ask} 的回答。
 *
 * <b>引用不是装饰。</b>{@code answer} 里的每个 {@code [n]} 都能在 {@code citations}
 * 里找到同编号的原文；模型输出没有有效引用时，服务会退回抽取式回答，不会返回
 * 一段无法核对的生成文本。
 *
 * <b>{@code answerable=false} 是本接口的合法结果</b>，不是错误：检索为空、
 * 检索降级导致无法确认、或模型明确说片段没覆盖时，都返回它，并在 {@code answer}
 * 里说明原因，而不是编一个答案。
 *
 * @param question       原始问题
 * @param casNo          请求限定的 CAS 号，未限定为 null
 * @param answerable     是否有足够依据作答
 * @param generated      true 表示回答由生成模型产生；false 表示抽取式原文片段
 * @param answer         回答正文，带 {@code [n]} 引用标记
 * @param citations      引用列表；编号与正文标记一致
 * @param degraded       检索或生成是否降级
 * @param degradedReason 降级原因，正常时为 null
 * @param indexing       后台是否仍在编码向量
 * @param needsRebuild   元数据与当前配置不一致，需要显式重建
 */
public record MsdsAnswer(
        String question,
        String casNo,
        boolean answerable,
        boolean generated,
        String answer,
        List<MsdsCitation> citations,
        boolean degraded,
        String degradedReason,
        boolean indexing,
        boolean needsRebuild
) {
}
