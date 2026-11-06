package com.lazybone.hazmat.dto;

import com.lazybone.hazmat.msds.MsdsIndexService;
import com.lazybone.hazmat.msds.MsdsVectorService;

/**
 * reindex 接口的返回：切块落库的结果 + 向量侧的处置方式。
 *
 * 两者分开是因为它们的代价完全不同：切块是本地文件扫描，向量可能触发模型调用。
 * 后台编码不阻塞接口，进度查 /api/msds/status；MQ 模式下的任务明细查
 * /api/msds/encode-tasks。
 *
 * {@code encodeTaskId} 只在 MQ 模式有值。MQ 关闭时编码仍走进程内 executor，
 * 没有可查询的任务行，返回 null。
 */
public record MsdsReindexResult(
        MsdsIndexService.RebuildResult rebuild,
        MsdsVectorService.ResetResult vectors,
        Long encodeTaskId) {
}
