package com.lazybone.hazmat.common;

/**
 * 服务暂时不可用：资源被占满或查询超时，稍后重试可能成功。
 *
 * 与 400 的区别是「调用方改参数也解决不了」——报表查询超时是因为数据量，
 * 不是查询条件写错了；与 500 的区别是「服务本身没坏」，别让监控把它当故障。
 * 调用方拿到 503 应该退避重试，拿到 500 应该报警。
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }
}
