package com.lazybone.hazmat.entity;

/**
 * 禁忌规则的依据来源类型。
 *
 * <p>当前只允许 MSDS 与 GB：MSDS 的 §10 禁配物是仓库内可逐条对照的原文；
 * GB 指国标条款，只有在拿到可引用的条款原文时才允许落库，不能凭印象填条款号。
 */
public enum IncompatibilitySourceType {
    /** 化学品安全技术说明书第 10 节（稳定性和反应性） */
    MSDS,
    /** 国家标准条款 */
    GB
}
