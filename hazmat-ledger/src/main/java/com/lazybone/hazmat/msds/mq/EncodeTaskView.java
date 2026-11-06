package com.lazybone.hazmat.msds.mq;

import com.lazybone.hazmat.entity.MsdsEncodeTask;
import com.lazybone.hazmat.entity.MsdsEncodeTaskStatus;

import java.time.LocalDateTime;

/**
 * 编码任务的接口视图。字段与排查时真正要看的信息一一对应：
 * 状态、尝试次数、失败原因、时间点，不把实体直接暴露出去。
 */
public record EncodeTaskView(
        Long id,
        String indexVersion,
        MsdsEncodeTaskStatus status,
        int attempts,
        int maxAttempts,
        String workerId,
        String lastError,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime finishedAt) {

    public static EncodeTaskView from(MsdsEncodeTask task) {
        return new EncodeTaskView(
                task.getId(),
                task.getIndexVersion(),
                task.getStatus(),
                task.getAttempts(),
                task.getMaxAttempts(),
                task.getWorkerId(),
                task.getLastError(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getFinishedAt());
    }
}
