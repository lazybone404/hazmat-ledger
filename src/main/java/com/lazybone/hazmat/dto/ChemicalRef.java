package com.lazybone.hazmat.dto;

import com.lazybone.hazmat.entity.Chemical;

/** 仅用于结果里标识一种试剂，不携带库存等易过期字段 */
public record ChemicalRef(Long id, String casNo, String name) {

    public static ChemicalRef from(Chemical chemical) {
        return new ChemicalRef(chemical.getId(), chemical.getCasNo(), chemical.getName());
    }
}
