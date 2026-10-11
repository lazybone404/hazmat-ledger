package com.lazybone.hazmat.dto;

import com.lazybone.hazmat.msds.MsdsIndexService;
import com.lazybone.hazmat.msds.MsdsVectorService;

/**
 * reindex 接口的返回：切块落库的结果 + 向量侧的处置方式。
 *
 * 两者分开是因为它们的代价完全不同：切块是本地文件扫描，向量可能触发模型调用。
 * 后台编码不阻塞接口，进度查 /api/msds/status。
 */
public record MsdsReindexResult(
        MsdsIndexService.RebuildResult rebuild,
        MsdsVectorService.ResetResult vectors) {
}
