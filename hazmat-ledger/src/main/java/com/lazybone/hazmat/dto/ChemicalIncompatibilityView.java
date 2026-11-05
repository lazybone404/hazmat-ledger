package com.lazybone.hazmat.dto;

import java.util.List;

/**
 * 单个试剂的混放检查：把该试剂与其它「还有库存」的试剂逐一比对。
 *
 * {@code conflicts} 只包含与该试剂**同柜**的禁忌组合；规则命中但分柜存放的
 * 放在 {@code separatedConflicts}。{@code locations} 是该试剂当前有实物库存的柜位，
 * 为空只可能出现在 {@code inStock=false} 的情况。
 *
 * @param chemical               被查询的试剂
 * @param inStock                该试剂当前是否有实物库存；没有时它不参与混放检查
 * @param locations              该试剂当前有库存的柜位
 * @param pairCount              实际比对的对数
 * @param coStoredPairCount      与之共用一个柜位的搭档数
 * @param conflicts              与它同柜的禁忌组合——真正的混放违规
 * @param separatedConflicts     规则命中但当前分柜的禁忌组合
 * @param uncoveredCount         未覆盖的搭档数
 * @param coStoredUncoveredCount 未覆盖且与它同柜的搭档数
 * @param uncovered              没有规则依据的搭档明细，不代表可以混放
 * @param message                面向使用者的说明
 */
public record ChemicalIncompatibilityView(
        ChemicalRef chemical,
        boolean inStock,
        List<String> locations,
        int pairCount,
        int coStoredPairCount,
        List<IncompatibilityView> conflicts,
        List<IncompatibilityView> separatedConflicts,
        int uncoveredCount,
        int coStoredUncoveredCount,
        List<UncoveredPair> uncovered,
        String message
) {

    public static ChemicalIncompatibilityView notInStock(ChemicalRef chemical) {
        return new ChemicalIncompatibilityView(
                chemical, false, List.of(), 0, 0, List.of(), List.of(), 0, 0, List.of(),
                "该试剂当前无库存，未参与混放检查");
    }

    public static ChemicalIncompatibilityView of(ChemicalRef chemical,
                                                 List<String> locations,
                                                 int pairCount,
                                                 int coStoredPairCount,
                                                 List<IncompatibilityView> conflicts,
                                                 List<IncompatibilityView> separatedConflicts,
                                                 List<UncoveredPair> uncovered,
                                                 int coStoredUncoveredCount) {
        return new ChemicalIncompatibilityView(
                chemical, true, List.copyOf(locations), pairCount, coStoredPairCount,
                List.copyOf(conflicts), List.copyOf(separatedConflicts),
                uncovered.size(), coStoredUncoveredCount, List.copyOf(uncovered),
                message(locations, pairCount, conflicts, separatedConflicts, uncovered));
    }

    private static String message(List<String> locations,
                                  int pairCount,
                                  List<IncompatibilityView> conflicts,
                                  List<IncompatibilityView> separated,
                                  List<UncoveredPair> uncovered) {
        if (pairCount == 0) {
            return "当前没有其它有库存的试剂可与之比对";
        }
        StringBuilder text = new StringBuilder();
        if (!conflicts.isEmpty()) {
            text.append("发现 ").append(conflicts.size()).append(" 对同柜禁忌混放");
        } else {
            text.append("未发现同柜禁忌混放");
        }
        if (!separated.isEmpty()) {
            text.append("；有 ").append(separated.size())
                    .append(" 对已知禁忌物与它分柜存放，须继续保持分离");
        }
        if (!uncovered.isEmpty()) {
            text.append("；").append(IncompatibilityReport.UNCOVERED_HINT);
        }
        if (locations.isEmpty()) {
            text.append("；该试剂没有登记柜位，无法判断物理隔离");
        }
        return text.toString();
    }
}
