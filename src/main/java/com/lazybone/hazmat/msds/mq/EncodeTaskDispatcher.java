package com.lazybone.hazmat.msds.mq;

/**
 * 触发 MSDS 编码的统一入口。
 *
 * <p>控制器和启动钩子只依赖这个接口，不判断 MQ 开没开：
 * MQ 关闭时本地实现直接调原有单线程 executor，返回 {@code null}；
 * MQ 开启时创建任务行并投递消息，返回任务快照。
 */
public interface EncodeTaskDispatcher {

    /**
     * @param indexVersion 要编码的索引版本；为空表示版本化之前的历史块
     * @param reason       触发来源，只用于日志（reindex / startup / test）
     * @return MQ 模式下的任务快照；本地模式返回 {@code null}
     */
    EncodeTaskView requestEncoding(String indexVersion, String reason);
}
