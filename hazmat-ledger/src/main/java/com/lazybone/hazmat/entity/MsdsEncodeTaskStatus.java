package com.lazybone.hazmat.entity;

/**
 * MSDS 编码任务状态。
 *
 * <p>只有 PENDING / FAILED 可以被 claim；RUNNING 只有在租约过期后才允许被另一个实例接管。
 * DEAD 不自动重试——它是「重试次数耗尽」或「编码已持久化暂停」的终态，需要人工 reindex。
 */
public enum MsdsEncodeTaskStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    DEAD
}
