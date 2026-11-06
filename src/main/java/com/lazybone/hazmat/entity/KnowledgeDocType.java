package com.lazybone.hazmat.entity;

/**
 * 知识源类型。三类文档的切法与引用单位不同，但共用同一套块表与检索链路。
 *
 * <p>存字符串而不是 ordinal：库里已经有数据，枚举顺序一变就会把历史块解释成另一种类型。
 */
public enum KnowledgeDocType {

    /** 化学品安全技术说明书，按 GB/T 16483 的 16 节切 */
    MSDS,
    /** 国家标准，按条款号切，引用单位是「第 5.2.1 条」 */
    GB,
    /** 标准作业程序 / 设备手册，按步骤或章节切 */
    SOP;

    /** 未知或空值按 MSDS 处理：历史数据只有这一类，不能因为字段缺失就让块搜不到 */
    public static KnowledgeDocType parse(String value) {
        if (value == null || value.isBlank()) {
            return MSDS;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return MSDS;
        }
    }
}
