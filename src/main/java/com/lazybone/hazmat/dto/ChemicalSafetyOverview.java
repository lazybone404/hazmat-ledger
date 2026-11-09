package com.lazybone.hazmat.dto;

import com.lazybone.hazmat.entity.Chemical;

import java.util.List;

/**
 * 安全摘要：固定取 MSDS 的四个关键章节，供领用详情页直接展示。
 *
 * <p>不做检索：领用人打开申请时最需要的是「这东西有什么危害、溅到身上怎么办、
 * 洒了怎么处理、要戴什么」，这些内容的位置是固定的（GB/T 16483 的第 2、4、6、8 节），
 * 不需要相关性排序。返回空章节时 {@code message} 会说明缺了哪几节，而不是伪造内容。
 *
 * @param available    该试剂是否已索引 MSDS 块
 * @param chemicalId   台账试剂 id
 * @param casNo        检索限定用的 CAS 号
 * @param chemicalName 台账里的名称
 * @param unit         台账单位，前端展示用
 * @param sections     已取到的章节，按节号升序；缺的节不会出现
 * @param message      面向用户的说明；四节齐全且可用时为 null
 */
public record ChemicalSafetyOverview(
        boolean available,
        Long chemicalId,
        String casNo,
        String chemicalName,
        String unit,
        List<Section> sections,
        String message) {

    /** 单节正文。同一节被切成多块时在服务层合并，这里一个节号只出现一次 */
    public record Section(int sectionNo, String title, String content) {
    }

    public static ChemicalSafetyOverview unavailable(Chemical chemical) {
        return new ChemicalSafetyOverview(false, chemical.getId(), chemical.getCasNo(),
                chemical.getName(), chemical.getUnit(), List.of(),
                "该试剂尚未索引 MSDS 原文，暂无法提供安全摘要");
    }
}
