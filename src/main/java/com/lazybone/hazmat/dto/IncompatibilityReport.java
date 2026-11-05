package com.lazybone.hazmat.dto;

import java.util.List;

/**
 * 全实验室的混放检查报告：只检查当前**还有实物库存**的试剂。
 *
 * <b>「有库存」不等于「混放」。</b>真正构成混放的条件是两种试剂至少有一个
 * 相同的批次柜位（{@code sharedLocations} 非空）。规则命中但当前分柜存放的组合
 * 单独放进 {@link #separatedConflicts}，它们是「必须继续保持分离」的提示，
 * 不是违规；只有 {@link #conflicts} 才是同柜违规。
 *
 * <b>{@code uncovered} 不是「安全组合」。</b>它只说明规则表里没有这一对的记录，
 * 可能本来就是安全的，也可能只是资料还没录。把两者分开返回，
 * 使用者才会知道哪些组合还需要人工确认；其中 {@code sharedLocations} 非空的
 * 尤其要优先补规则。
 *
 * @param chemicalCount            参与比对的试剂数（有库存的）
 * @param locationCount            这些试剂分布在多少个不同柜位
 * @param pairCount                比对对数 = chemicalCount * (chemicalCount - 1) / 2
 * @param coStoredPairCount        至少共用一个柜位的对数
 * @param conflicts                同柜且命中禁忌关系——真正的混放违规
 * @param separatedConflicts       命中禁忌关系但当前分柜存放，共 0 个共享柜位
 * @param uncoveredCount           未覆盖组合数
 * @param coStoredUncoveredCount   未覆盖且当前同柜的组合数
 * @param uncovered                未覆盖组合明细，与两类 conflict 互斥且并集为全部比对对
 * @param message                  面向使用者的整体说明
 */
public record IncompatibilityReport(
        int chemicalCount,
        int locationCount,
        int pairCount,
        int coStoredPairCount,
        List<IncompatibilityView> conflicts,
        List<IncompatibilityView> separatedConflicts,
        int uncoveredCount,
        int coStoredUncoveredCount,
        List<UncoveredPair> uncovered,
        String message
) {

    static final String UNCOVERED_HINT =
            "未覆盖组合仅表示规则表里没有记录，不代表可以混放，请人工核对";

    public static IncompatibilityReport of(int chemicalCount,
                                           int locationCount,
                                           int pairCount,
                                           int coStoredPairCount,
                                           List<IncompatibilityView> conflicts,
                                           List<IncompatibilityView> separatedConflicts,
                                           List<UncoveredPair> uncovered,
                                           int coStoredUncoveredCount) {
        return new IncompatibilityReport(
                chemicalCount, locationCount, pairCount, coStoredPairCount,
                List.copyOf(conflicts), List.copyOf(separatedConflicts),
                uncovered.size(), coStoredUncoveredCount, List.copyOf(uncovered),
                message(chemicalCount, conflicts, separatedConflicts, uncovered));
    }

    private static String message(int chemicalCount,
                                  List<IncompatibilityView> conflicts,
                                  List<IncompatibilityView> separated,
                                  List<UncoveredPair> uncovered) {
        if (chemicalCount < 2) {
            return "当前有库存的试剂不足两种，没有可比的组合";
        }
        StringBuilder text = new StringBuilder();
        if (!conflicts.isEmpty()) {
            text.append("发现 ").append(conflicts.size()).append(" 对同柜禁忌混放");
        } else {
            text.append("未发现同柜禁忌混放");
        }
        if (!separated.isEmpty()) {
            text.append("；有 ").append(separated.size())
                    .append(" 对已知禁忌物当前分柜存放，须继续保持分离");
        }
        if (!uncovered.isEmpty()) {
            text.append("；").append(UNCOVERED_HINT);
        }
        return text.toString();
    }
}
