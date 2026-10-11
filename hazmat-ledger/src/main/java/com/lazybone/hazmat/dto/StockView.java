package com.lazybone.hazmat.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 库存视图：某试剂当前的批次明细 + 可用总量 + 预警。
 *
 * 把「预警」算在服务端而不是让前端自己按日期比，原因是**判定口径要统一**：
 * 什么算临期（30 天内）、什么算低库存，这套阈值将来会和定时任务、报表共用，
 * 分散到前端就有多份实现，迟早不一致。
 */
public record StockView(
        Long chemicalId,
        String chemicalName,
        String unit,
        /** 真正可用的量：**不含已过期批次**。含过期量的台账总数在 alerts 里会说明 */
        BigDecimal totalAvailable,
        List<BatchResponse> batches,
        List<String> alerts) {
}
