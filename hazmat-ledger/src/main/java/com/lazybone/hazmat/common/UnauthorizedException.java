package com.lazybone.hazmat.common;

/** 未登录、凭证错误或 token 失效。映射为 401，与 400 业务错误区分。 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
