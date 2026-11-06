package com.lazybone.hazmat.dto;

import com.lazybone.hazmat.entity.Chemical;
import com.lazybone.hazmat.entity.ChemicalIncompatibility;

import java.util.List;

/**
 * 一条命中的禁忌关系。
 *
 * 把两种试剂都带上，调用方不用再按 CAS 反查档案；
 * {@code reason} 与 {@code source} 原样带出，让使用者能自己判断依据是否适用，
 * 而不是只看到一个「冲突」结论。
 *
 * <b>{@code sharedLocations} 是这条关系是否真的构成「混放」的判据。</b>
 * 非空表示两种试剂至少有一个共同柜位，是实际混放；为空表示规则命中但当前分开放置，
 * 只应提示「保持分离」，不能报警成同柜违规。
 *
 * <p>{@code basis} 是结构化依据；为 {@code null} 表示这条规则还没有可对照的条款/摘录，
 * 不能当成「已核实」展示。
 */
public record IncompatibilityView(
        ChemicalRef chemicalA,
        ChemicalRef chemicalB,
        List<String> sharedLocations,
        String reason,
        String source,
        RuleBasis basis
) {

    public static IncompatibilityView of(Chemical a, Chemical b,
                                         List<String> sharedLocations,
                                         ChemicalIncompatibility rule) {
        return new IncompatibilityView(
                ChemicalRef.from(a), ChemicalRef.from(b), List.copyOf(sharedLocations),
                rule.getReason(), rule.getSource(), RuleBasis.from(rule));
    }
}
