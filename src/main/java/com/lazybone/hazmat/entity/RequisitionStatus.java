package com.lazybone.hazmat.entity;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 领用申请的状态机。
 *
 * 把「允许怎么流转」写在枚举里，而不是散在 Service 的一堆 if 里，有两个实际好处：
 * 一是**流转规则只有一处**，改规则不会漏改分支；
 * 二是测试可以直接对着这张表写，不用去猜哪些组合合法。
 *
 * <pre>
 *   PENDING ──审批通过（普通试剂，一人）──────────> APPROVED ──出库──> ISSUED ──核销──> CLOSED
 *      │  └──第一人通过（高危试剂，双人）──> PENDING_SECOND ──第二人通过──┘
 *      │                                        │
 *      ├──审批驳回──> REJECTED <─────审批驳回────┘
 *      └──申请人撤回─> CANCELLED <──申请人撤回──┘（APPROVED 也能撤回）
 * </pre>
 *
 * 注意 REJECTED / CLOSED / CANCELLED 是**终态**：进去就出不来。终态不可逆这条规则能挡住
 * 「已驳回的申请又被审批通过」这类因并发或重放产生的脏数据。
 *
 * PENDING_SECOND 是「双人复核」的实现方式，理由写在那个枚举项上。
 */
public enum RequisitionStatus {

    /** 已提交，等审批 */
    PENDING,
    /**
     * 高危试剂（{@code hazardLevel=高}）已经被第一位审批人通过，等**另一位**审批人复核。
     *
     * <b>为什么把「等第二个不同的人」做成一个状态，而不是留在 PENDING 上另外记一个计数：</b>
     * <ol>
     *   <li><b>「下一个合法动作是什么」本来就是状态的职责。</b>停在 PENDING 的话，
     *       第二次审批和第一次审批从状态上看一模一样，于是「还差几个人」这条规则只能
     *       漏到 Service 的 if 里——而本项目的约定是流转规则只此一处。</li>
     *   <li><b>留痕表不需要新字段就能区分第一人和第二人。</b>第一人写的是
     *       {@code PENDING → PENDING_SECOND}，第二人写的是
     *       {@code PENDING_SECOND → APPROVED}。语义由状态机赋予，不用再造一个
     *       「这是第几次审批」的列。</li>
     *   <li><b>中间态天然挡住「只批一次就出库」。</b>PENDING_SECOND 没有通向 ISSUED 的边，
     *       没走完复核就出库会被状态机拦下，不靠 Service 自己记得检查。</li>
     * </ol>
     * 代价是多一个状态、多两条边；换来的是规则仍然可以穷举、测试可以对着这张表写。
     *
     * 注意「两个审批人必须是不同的人」<b>不在这张表里</b>——它取决于「上一个审批人是谁」，
     * 是数据而不是状态。分工是：**枚举管状态到状态合不合法，Service 管谁有资格走这条边。**
     */
    PENDING_SECOND,
    /** 审批通过，等出库 */
    APPROVED,
    /** 审批驳回（终态） */
    REJECTED,
    /** 已出库，等着核销 */
    ISSUED,
    /** 已核销（终态） */
    CLOSED,
    /** 已撤回（终态） */
    CANCELLED;

    private static final Map<RequisitionStatus, Set<RequisitionStatus>> ALLOWED = Map.of(
            PENDING, EnumSet.of(APPROVED, PENDING_SECOND, REJECTED, CANCELLED),
            // 只有 PENDING_SECOND 能走到 APPROVED 这一步。谁能走由 Service 判（不能是同一个审批人）
            PENDING_SECOND, EnumSet.of(APPROVED, REJECTED, CANCELLED),
            APPROVED, EnumSet.of(ISSUED, CANCELLED),
            ISSUED, EnumSet.of(CLOSED),
            REJECTED, EnumSet.noneOf(RequisitionStatus.class),
            CLOSED, EnumSet.noneOf(RequisitionStatus.class),
            CANCELLED, EnumSet.noneOf(RequisitionStatus.class));

    /** 从当前状态能不能走到目标状态 */
    public boolean canTransitionTo(RequisitionStatus target) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    /** 是不是走完了。终态不允许再流转，Service 里用它挡重放 */
    public boolean isFinal() {
        return ALLOWED.getOrDefault(this, Set.of()).isEmpty();
    }

    public Set<RequisitionStatus> allowedTargets() {
        return ALLOWED.getOrDefault(this, Set.of());
    }
}
