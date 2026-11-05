package com.lazybone.hazmat.dto;

import com.lazybone.hazmat.entity.Chemical;

import java.util.List;

/**
 * 规则表里查不到的一对试剂。
 *
 * 它代表「没有依据」，不代表「可以混放」。单独成类型是为了在接口契约上
 * 把这两种结论分开——如果混在冲突列表里用空 reason 表示，前端迟早会当成安全。
 *
 * {@code sharedLocations} 非空说明这两种试剂**当前确实同柜**，只是规则表没收录，
 * 优先级比普通未覆盖更高；为空则只是「有潜在组合、但眼下分开放」。
 */
public record UncoveredPair(
        ChemicalRef chemicalA,
        ChemicalRef chemicalB,
        List<String> sharedLocations
) {

    public static UncoveredPair of(Chemical a, Chemical b, List<String> sharedLocations) {
        return new UncoveredPair(
                ChemicalRef.from(a), ChemicalRef.from(b), List.copyOf(sharedLocations));
    }
}
