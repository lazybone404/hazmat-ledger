package com.lazybone.hazmat.entity;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 报废单状态机。
 *
 * <pre>
 *   PENDING ──审批通过──> APPROVED（终态）
 *      ├──审批驳回────> REJECTED（终态）
 *      └──申请人撤销──> CANCELLED（终态）
 * </pre>
 *
 * 与领用申请一样，流转规则写在枚举里：新增动作时只改这一处，Service 不再散落 if。
 * APPROVED 也是终态——库存已经扣掉、流水已经写下，不存在「再批一次」的合法路径。
 */
public enum ScrapOrderStatus {

    /** 已提交，等审批 */
    PENDING,
    /** 审批通过，库存已扣减（终态） */
    APPROVED,
    /** 审批驳回，库存未动（终态） */
    REJECTED,
    /** 申请人撤销，库存未动（终态） */
    CANCELLED;

    private static final Map<ScrapOrderStatus, Set<ScrapOrderStatus>> ALLOWED = Map.of(
            PENDING, EnumSet.of(APPROVED, REJECTED, CANCELLED),
            APPROVED, EnumSet.noneOf(ScrapOrderStatus.class),
            REJECTED, EnumSet.noneOf(ScrapOrderStatus.class),
            CANCELLED, EnumSet.noneOf(ScrapOrderStatus.class));

    public boolean canTransitionTo(ScrapOrderStatus target) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    public boolean isFinal() {
        return ALLOWED.getOrDefault(this, Set.of()).isEmpty();
    }

    public Set<ScrapOrderStatus> allowedTargets() {
        return ALLOWED.getOrDefault(this, Set.of());
    }
}
