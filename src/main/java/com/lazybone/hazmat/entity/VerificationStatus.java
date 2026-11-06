package com.lazybone.hazmat.entity;

/**
 * 禁忌规则依据的核实状态。
 *
 * <p>三态里的第三态「未覆盖」不在本枚举里：它表示规则表里根本没有这一对，
 * 由接口的 {@code uncovered} 列表表达。规则存在但依据未核实是 {@link #PENDING}，
 * 不能用「有规则」冒充「已核实」。
 */
public enum VerificationStatus {
    /** 依据能逐条对照到原文 */
    VERIFIED,
    /** 有规则，但出处尚未核实或缺少条款级依据 */
    PENDING
}
