package com.lazybone.hazmat.common;

/**
 * 业务规则被违反时抛这个（库存不足、状态不允许流转、编号重复……）。
 * 对应 HTTP 400，和「资源不存在」（404）分开——这个区分对前端很有用：
 * 400 是用户能自己改的，404 是改也没用。
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
