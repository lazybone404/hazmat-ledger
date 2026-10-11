package com.lazybone.hazmat.dto;

import com.lazybone.hazmat.entity.Chemical;
import com.lazybone.hazmat.entity.ChemicalBatch;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 效期预警里的一条批次。
 *
 * 刻意带上化学品名与单位：预警列表是给人看的待办清单，
 * 只有批次号的话还得再去查一次试剂档案才知道要去处理什么。
 *
 * <b>预警按 {@code usableUntil} 而不是 {@code expireDate} 排。</b>
 * 一批开封后只剩 3 天的甲醇，比一批「包装有效期还有 20 天」的甲醇更急；
 * 按包装有效期排会把它排在后面，甚至完全不出现在预警里。
 */
public record ExpiringBatchResponse(
        Long batchId,
        Long chemicalId,
        String chemicalName,
        String unit,
        String batchNo,
        BigDecimal availableQuantity,
        String location,
        LocalDate expireDate,
        /** 实际可用截止日：开封后可能早于 expireDate */
        LocalDate usableUntil,
        /** 距可用截止日的天数，负数表示已过可用期 */
        long daysToUsable) {

    public static ExpiringBatchResponse from(ChemicalBatch b, Chemical c, LocalDate today) {
        LocalDate usableUntil = b.getUsableUntil() == null ? b.getExpireDate() : b.getUsableUntil();
        return new ExpiringBatchResponse(
                b.getId(), b.getChemicalId(), c == null ? "未知试剂" : c.getName(),
                c == null ? "" : c.getUnit(),
                b.getBatchNo(), b.getAvailableQuantity(), b.getLocation(),
                b.getExpireDate(), usableUntil,
                usableUntil.toEpochDay() - today.toEpochDay());
    }
}
