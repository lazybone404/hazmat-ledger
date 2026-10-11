package com.lazybone.hazmat.dto;

import com.lazybone.hazmat.entity.ChemicalBatch;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 批次视图。
 *
 * <b>两个日期要分清楚：</b>
 * <ul>
 *   <li>{@code expireDate} —— 供应商标注的包装有效期，前提是未开封，入库后不再变。</li>
 *   <li>{@code usableUntil} —— 系统判定的实际可用截止日。开封后可能提前，
 *       出库过滤、可用量统计、效期预警都用它。</li>
 * </ul>
 * 前端标红、倒计时都该看 {@code daysToUsable}，看 {@code expireDate} 会把一瓶
 * 开封三个月、早已不能用的甲醇显示成「还有半年」。
 */
public record BatchResponse(
        Long id,
        Long chemicalId,
        String batchNo,
        BigDecimal initialQuantity,
        BigDecimal availableQuantity,
        String location,
        LocalDate expireDate,
        /** 实际可用截止日；开封后可能早于 expireDate */
        LocalDate usableUntil,
        /** 开封时间，为空表示未开封 */
        LocalDateTime openedAt,
        String openedBy,
        /** 距可用截止日的天数，负数表示已过可用期。前端据此标红 */
        long daysToUsable) {

    public static BatchResponse from(ChemicalBatch b, LocalDate today) {
        LocalDate usableUntil = b.getUsableUntil() == null ? b.getExpireDate() : b.getUsableUntil();
        return new BatchResponse(b.getId(), b.getChemicalId(), b.getBatchNo(),
                b.getInitialQuantity(), b.getAvailableQuantity(), b.getLocation(),
                b.getExpireDate(), usableUntil, b.getOpenedAt(), b.getOpenedBy(),
                usableUntil.toEpochDay() - today.toEpochDay());
    }
}
