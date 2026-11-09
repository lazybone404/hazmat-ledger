package com.lazybone.hazmat.dto;

import jakarta.validation.constraints.Size;

/**
 * 报废审批。与领用审批分开定义，而不是复用 {@link ReviewRequest}：
 * 两张单的语义将来可能分叉（比如报废要填销毁方式），共用请求体反而会互相牵制。
 *
 * <p>没有 reviewer 字段：审批人来自登录凭证。允许客户端自报审批人，
 * 「不能自己批自己」的规则就可以被一个改参数的动作绕过。
 */
public record ScrapReviewRequest(
        boolean approved,
        @Size(max = 500) String comment) {
}
