package com.lazybone.hazmat.common;

/** 已登录但无权操作。映射为 403，不要和「业务规则不允许」的 400 混在一起。 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
