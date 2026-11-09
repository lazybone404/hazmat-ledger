package com.lazybone.hazmat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 报废审批单。
 *
 * <p>原先报废只有「管理员直接扣减」一个入口，适合应急，但不适合常规流程：
 * 谁申请、谁批、为什么报废，全都没有单据可查。这张表把报废拆成
 * 「申请 → 审批 → 扣减」，扣减仍然发生在审批通过的那一刻、同一个事务里，
 * 这样单据状态与库存流水不会各说各话。
 *
 * <p>批次号存快照（{@code batchNo}），理由与盘点明细相同：台账要能回答
 * 「当时报废的是哪一瓶」，而批次行将来可能被改名或清理。审批时仍按
 * {@code batchId} 扣减，快照只用于展示与追溯。
 *
 * <p>用 {@code @Version} 乐观锁：审批必须先读出来看状态，属于典型的先读后写；
 * 两个人同时点通过时，后写的那一个必须被拦下，否则同一张单可能扣两次库存。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "scrap_order",
        uniqueConstraints = @UniqueConstraint(name = "uk_scrap_order_no", columnNames = "scrap_no"),
        indexes = {
                @Index(name = "idx_scrap_order_status_created", columnList = "status,createdAt"),
                @Index(name = "idx_scrap_order_applicant_created", columnList = "applicant,createdAt")
        })
public class ScrapOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 报废单号，对外可见的业务主键，由号段服务生成 */
    @Column(name = "scrap_no", nullable = false, unique = true, length = 40)
    private String scrapNo;

    @Column(name = "chemical_id", nullable = false)
    private Long chemicalId;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    /** 批次号快照，见类注释 */
    @Column(name = "batch_no", nullable = false, length = 50)
    private String batchNo;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScrapOrderStatus status;

    @Column(nullable = false, length = 100)
    private String applicant;

    @Column(length = 100)
    private String reviewer;

    @Column(name = "review_comment", length = 500)
    private String reviewComment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Version
    private Long version;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = ScrapOrderStatus.PENDING;
        }
    }
}
