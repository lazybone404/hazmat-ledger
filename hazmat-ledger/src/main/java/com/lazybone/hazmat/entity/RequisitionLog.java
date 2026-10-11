package com.lazybone.hazmat.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 申请的状态流转记录 —— 每一步谁改的、什么时候改的、为什么。
 *
 * 危化品台账的核心诉求之一是**可追溯**：出了问题要能回答「这瓶东西谁批的、
 * 谁发的、什么理由」。只存一个当前状态答不了这个问题，所以每次流转都追加一条。
 *
 * 只追加、不修改、不删除（append-only）。这类表的价值全在完整性上，
 * 一旦允许改，追溯就失去意义了。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "requisition_log",
        indexes = @Index(name = "idx_log_req", columnList = "requisitionId"))
public class RequisitionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long requisitionId;

    /**
     * 流转前的状态。**允许为空**：创建申请那一条没有「前一个状态」。
     * 不为了统一而硬填一个假值（比如填 PENDING），那会让时间线上出现
     * 两条 PENDING → PENDING 的记录，看的人得先分辨哪条是真流转。
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private RequisitionStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RequisitionStatus toStatus;

    @Column(nullable = false, length = 100)
    private String operator;

    @Column(length = 300)
    private String comment;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
