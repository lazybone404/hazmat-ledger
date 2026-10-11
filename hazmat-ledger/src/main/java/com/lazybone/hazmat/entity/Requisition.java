package com.lazybone.hazmat.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 领用申请。
 *
 * 这里用 {@code @Version} 乐观锁，和批次的扣减方式不同，原因是**这个流程必须先读后写**：
 * 得先把申请读出来看它是不是 PENDING，才知道能不能批。中间这段时间另一个审批人
 * 可能已经批了，所以写回时要比对版本号，冲突就拒绝。
 *
 * 两个场景放在一起看：
 * <pre>
 *   扣库存  不需要读 → 条件更新（WHERE available >= qty），一条语句交给数据库判断
 *   改状态  必须先读 → 版本号乐观锁，读时拿版本、写时比对
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "requisition",
        indexes = {
                @Index(name = "idx_req_applicant", columnList = "applicant"),
                @Index(name = "idx_req_status", columnList = "status")
        })
public class Requisition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 申请单号，对外可见的业务主键 */
    @Column(nullable = false, unique = true, length = 40)
    private String reqNo;

    @Column(nullable = false, length = 100)
    private String applicant;

    @Column(nullable = false)
    private Long chemicalId;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    /** 用途。危化品管理里这是必填项，也是审批人判断该不该批的主要依据 */
    @Column(nullable = false, length = 500)
    private String purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RequisitionStatus status;

    @Column(length = 100)
    private String reviewer;

    @Column(length = 500)
    private String reviewComment;

    /**
     * 双人复核里**第一位**审批人的签名。只有高危试剂且走完第一步才有值。
     *
     * <b>为什么要单独占一列，而不是去查留痕表。</b>「上一次是谁批的」是
     * **当前状态的一部分**——它就是「这第二个人能不能算」的唯一判据。既然主表已经
     * 存了 {@code reviewer}（最终决定人）而不是每次去查日志，第一位审批人同理：
     * 同一条 UPDATE 里把 {@code status}、{@code first_reviewer} 一起写掉，
     * 整个「成为第一人」的动作才是原子的，也才落在 {@code @Version} 的保护范围内。
     * 去查日志的话，读日志和写主表是两次读，中间那个窗口正好是并发要出问题的地方。
     *
     * 没给它配 comment / 时间戳三件套（不像 {@code reviewer} 那样成套），因为
     * **没有人需要**：第一人的意见和时刻都已经在 {@code RequisitionLog} 里了，
     * 详情接口返回的时间线就带着它们。冗余字段只有在有读者时才值得留
     * （对比 {@code StockLedger.balanceAfter}：它有对账这个读者，所以值得留）。
     */
    @Column(length = 100)
    private String firstReviewer;

    /** 出库时实际扣的批次。审批时不指定，出库时由系统按先到期先出挑 */
    private Long issuedBatchId;

    /** 出库时实际扣掉的量。可能和申请量不同（比如只够发一部分） */
    @Column(precision = 12, scale = 3)
    private BigDecimal issuedQuantity;

    /**
     * 乐观锁版本号。Hibernate 会在 UPDATE 的 where 里自动带上它，
     * 并在受影响行数为 0 时抛 OptimisticLockException。
     */
    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime reviewedAt;

    private LocalDateTime issuedAt;

    private LocalDateTime closedAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = RequisitionStatus.PENDING;
        }
    }
}
