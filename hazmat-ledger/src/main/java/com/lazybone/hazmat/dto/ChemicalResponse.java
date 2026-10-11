package com.lazybone.hazmat.dto;

import com.lazybone.hazmat.entity.Chemical;

import java.time.LocalDateTime;

public record ChemicalResponse(
        Long id,
        String casNo,
        String name,
        String category,
        String hazardLevel,
        String unit,
        String spec,
        String msdsUrl,
        /** 开封后可用天数；为空表示不做开封后追踪 */
        Integer postOpenDays,
        LocalDateTime createdAt) {

    public static ChemicalResponse from(Chemical c) {
        return new ChemicalResponse(c.getId(), c.getCasNo(), c.getName(), c.getCategory(),
                c.getHazardLevel(), c.getUnit(), c.getSpec(), c.getMsdsUrl(),
                c.getPostOpenDays(), c.getCreatedAt());
    }
}
