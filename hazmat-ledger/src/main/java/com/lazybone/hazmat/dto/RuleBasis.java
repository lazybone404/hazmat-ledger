package com.lazybone.hazmat.dto;

import com.lazybone.hazmat.entity.ChemicalIncompatibility;

/**
 * 一条禁忌规则的结构化依据。
 *
 * <p>{@code excerpt} 是从 MSDS 第 10 节抄下来的原文摘录，不是模型生成的解释；
 * 前端可以把它和 {@code reason} 一起展示，让使用者自己判断依据是否适用。
 *
 * @param type              来源类型：MSDS / GB
 * @param doc               依据文档，如「MSDS 7722-64-7 / MSDS 7647-01-0」
 * @param clause            条款位置，如「第10节 稳定性和反应性·禁配物」
 * @param excerpt           原文摘录
 * @param verificationStatus VERIFIED 表示已对照原文；PENDING 表示有规则但依据未核实
 */
public record RuleBasis(
        String type,
        String doc,
        String clause,
        String excerpt,
        String verificationStatus
) {

    /**
     * 规则没有任何结构化依据时返回 {@code null}，而不是拼一个空对象。
     *
     * <p>「没有依据」和「依据为空字符串」在统计上必须区分：前者计入 missing，
     * 后者说明数据有问题，应该修数据而不是当成缺失。
     */
    public static RuleBasis from(ChemicalIncompatibility rule) {
        if (rule.getSourceDoc() == null
                && rule.getSourceClause() == null
                && rule.getSourceExcerpt() == null) {
            return null;
        }
        return new RuleBasis(
                rule.getSourceType() == null ? null : rule.getSourceType().name(),
                rule.getSourceDoc(),
                rule.getSourceClause(),
                rule.getSourceExcerpt(),
                rule.getVerificationStatus() == null ? null : rule.getVerificationStatus().name());
    }
}
