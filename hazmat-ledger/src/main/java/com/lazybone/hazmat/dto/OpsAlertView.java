package com.lazybone.hazmat.dto;

import com.lazybone.hazmat.entity.AlertSeverity;
import com.lazybone.hazmat.entity.OpsAlert;

import java.time.LocalDateTime;

/** 运维告警的对外视图。实体字段全部可展示，这里仍然单独建 DTO，避免接口直接绑在表结构上。 */
public record OpsAlertView(
        Long id,
        String alertType,
        AlertSeverity severity,
        String fingerprint,
        String message,
        int occurrences,
        LocalDateTime firstSeenAt,
        LocalDateTime lastSeenAt,
        boolean resolved,
        LocalDateTime resolvedAt,
        String resolvedBy) {

    public static OpsAlertView from(OpsAlert alert) {
        return new OpsAlertView(
                alert.getId(),
                alert.getAlertType(),
                alert.getSeverity(),
                alert.getFingerprint(),
                alert.getMessage(),
                alert.getOccurrences() == null ? 0 : alert.getOccurrences(),
                alert.getFirstSeenAt(),
                alert.getLastSeenAt(),
                alert.getResolvedAt() != null,
                alert.getResolvedAt(),
                alert.getResolvedBy());
    }
}
