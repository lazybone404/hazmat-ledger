package com.lazybone.hazmat.common;

/**
 * 请求本身合法，但执行时数据已经变了（库存被别的单据扣走、并发审批撞车）。
 *
 * <p>与 {@link BusinessException} 的区别在这里：400 表示「你提交的内容有问题，改一下再来」；
 * 409 表示「你提交的内容没问题，只是世界和提交时不一样了」——调用方该做的是刷新后重试，
 * 而不是改参数。最容易混的就是报废审批：创建时校验过余量，审批时却可能不够了。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
