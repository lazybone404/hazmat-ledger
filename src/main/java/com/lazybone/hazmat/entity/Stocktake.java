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
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 盘点单。
 *
 * <b>它解决的问题：</b>系统记的是「应该有多少」，柜子里是「实际有多少」。
 * 移液没登记、倾洒没上报、拿错瓶子，账就会慢慢偏离实物，而入库/出库/报废
 * 这些流水本身没有任何机制能发现这个偏离——它们只在账内自洽。
 * 盘点是唯一能把两条线对上的动作，差异必须落成一条 ADJUST 流水，
 * 而不是直接改数字：台账只增不改，任何变化都要留痕。
 *
 * <b>为什么用 {@code @Version}：</b>状态变更必须先读出来判断（是不是 DRAFT、
 * 是不是 SUBMITTED），属于典型的「先读后写」。两个人同时点确认时，
 * 后写的那个必须被拦下——否则差异会被调两次。这和领用申请的情况一样。
 * 对照库存扣减：那边不需要读，所以用条件更新。
 *
 * <b>为什么创建人、复核人、确认人分成三组字段：</b>盘点制度上要求「盘的人」
 * 「复核的人」「确认的人」分开。默认只留痕、不强制——单管理员部署下强制分离
 * 会导致没人能完成流程。需要收紧时用 {@code hazmat.stocktake.require-review}
 * 与 {@code hazmat.stocktake.require-separate-confirmer} 打开，不用改表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "stocktake",
        indexes = @Index(name = "idx_stocktake_status_created", columnList = "status,createdAt"))
public class Stocktake {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 盘点单号，对外可见的业务主键，规则同申请单（见 StocktakeService.nextStocktakeNo） */
    @Column(nullable = false, unique = true, length = 40)
    private String stocktakeNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StocktakeStatus status;

    @Column(length = 500)
    private String remark;

    @Column(nullable = false, length = 100)
    private String createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(length = 100)
    private String submittedBy;

    private LocalDateTime submittedAt;

    @Column(length = 100)
    private String confirmedBy;

    private LocalDateTime confirmedAt;

    /**
     * 复核人、复核时间、复核意见。
     *
     * <p>三个字段都允许为空：复核是可选的（{@code hazmat.stocktake.require-review}），
     * 历史盘点单也没有复核这一步。打开强制复核后，SUBMITTED 必须先经过这里才能确认。
     */
    @Column(name = "reviewed_by", length = 100)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_comment", length = 500)
    private String reviewComment;

    /** 乐观锁：状态流转必须比对这个版本号，理由见类注释 */
    @Version
    private Long version;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = StocktakeStatus.DRAFT;
        }
    }
}
