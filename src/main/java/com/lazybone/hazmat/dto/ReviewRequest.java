package com.lazybone.hazmat.dto;

import jakarta.validation.constraints.Size;

/**
 * 审批。通过和驳回共用一个请求体，用 {@code approved} 区分。
 *
 * 为什么不做成两个接口（/approve 和 /reject）：它们改的是同一张表的同两个字段、
 * 走同一套权限和同一条状态流转规则，拆开只会让规则复制两份。
 *
 * 没有 reviewer 字段：审批人来自登录凭证。双人复核靠「两次请求的用户名不同」判定，
 * 如果审批人能自己填名字，这条规则就形同虚设。
 */
public record ReviewRequest(
        boolean approved,
        @Size(max = 500) String comment) {
}
