package com.lazybone.hazmat.entity;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 盘点单的状态机。
 *
 * <pre>
 *   DRAFT ──提交──> SUBMITTED ──确认并调账──> CONFIRMED
 *     │                 │
 *     └──撤销───────────┴──撤销──> CANCELLED
 * </pre>
 *
 * 为什么要有 DRAFT 和 SUBMITTED 两段，而不是「一录完就调账」：
 * <ol>
 *   <li><b>录入和确认是两个人的动作。</b>盘点人对着柜子数，确认人看一眼差异再调账。
 *       合成一步的话，手滑输错一个 0 就直接改进了台账。</li>
 *   <li><b>差异需要被看见。</b>SUBMITTED 这一档让「有差异」本身成为可查询的状态，
 *       管理员可以先看差异清单再决定确认还是撤销。</li>
 *   <li><b>调账是不可逆的。</b>台账只增不改，一旦写出 ADJUST 流水，就只剩再调整一次
 *       这条路。把「决定调账」单独做成一次显式动作，是这类数据的常规做法。</li>
 * </ol>
 *
 * 终态 CONFIRMED / CANCELLED 不允许再流转：防止「已取消的盘点单又被确认」
 * 或者重复确认导致同一差异被调两次。
 */
public enum StocktakeStatus {

    /** 草稿：可以录入实盘量 */
    DRAFT,
    /** 已提交：实盘量齐了，等确认。此时不能再改数，只能确认或撤销 */
    SUBMITTED,
    /** 已确认：差异已经写进台账（终态） */
    CONFIRMED,
    /** 已撤销：没有调账（终态） */
    CANCELLED;

    private static final Map<StocktakeStatus, Set<StocktakeStatus>> ALLOWED = Map.of(
            DRAFT, EnumSet.of(SUBMITTED, CANCELLED),
            SUBMITTED, EnumSet.of(CONFIRMED, CANCELLED),
            CONFIRMED, EnumSet.noneOf(StocktakeStatus.class),
            CANCELLED, EnumSet.noneOf(StocktakeStatus.class));

    public boolean canTransitionTo(StocktakeStatus target) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    public Set<StocktakeStatus> allowedTargets() {
        return ALLOWED.getOrDefault(this, Set.of());
    }
}
