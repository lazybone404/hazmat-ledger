package com.lazybone.hazmat.dto;

import com.lazybone.hazmat.entity.VerificationStatus;

import java.util.List;

/**
 * 本次混放检查中，命中规则的结构化依据覆盖情况。
 *
 * <p>只统计「规则命中」的组合（同柜 + 分柜），未覆盖组合是另一件事：
 * 前者说明规则表有记录，后者说明规则表根本没有这一对。把两者合成一个比例，
 * 会让「规则少」看起来像「依据全」。
 *
 * @param total            命中的规则条数
 * @param verified         依据已核实（VERIFIED 且有 doc/clause/excerpt）
 * @param pending          有规则但依据未核实（PENDING）
 * @param missing          有规则但没有任何结构化依据
 * @param verifiedRatio    verified / total，保留两位小数；total=0 时为 0
 */
public record BasisCoverage(
        int total,
        int verified,
        int pending,
        int missing,
        double verifiedRatio
) {

    public static BasisCoverage of(List<IncompatibilityView> conflicts,
                                   List<IncompatibilityView> separated) {
        int total = conflicts.size() + separated.size();
        int verified = 0;
        int pending = 0;
        int missing = 0;
        for (IncompatibilityView view : concat(conflicts, separated)) {
            RuleBasis basis = view.basis();
            if (basis == null) {
                missing++;
            } else if (VerificationStatus.VERIFIED.name().equals(basis.verificationStatus())
                    && hasText(basis.doc(), basis.clause(), basis.excerpt())) {
                verified++;
            } else {
                pending++;
            }
        }
        double ratio = total == 0 ? 0.0 : Math.round(verified * 100.0 / total) / 100.0;
        return new BasisCoverage(total, verified, pending, missing, ratio);
    }

    private static List<IncompatibilityView> concat(List<IncompatibilityView> a,
                                                    List<IncompatibilityView> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }

    private static boolean hasText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }
}
