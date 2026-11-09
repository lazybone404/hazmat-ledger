package com.lazybone.hazmat.entity;

/**
 * 运维告警级别。只区分「知道就行」「该看一眼」「要马上处理」三档：
 * 分级太细会让运维在告警列表里做二次判断，这里的目的只是排序与筛选。
 */
public enum AlertSeverity {

    /** 信息：可以忽略，例如例行扫描完成 */
    INFO,
    /** 警告：需要关注，例如临期批次、盘点单卡住 */
    WARN,
    /** 严重：数据或流程已经不平，例如对账不一致 */
    CRITICAL
}
