package com.lazybone.hazmat.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 试剂批次 —— 一个试剂每次入库生成一条，库存记在这一层。
 *
 * 两条设计要点：
 *
 * **一是用 BigDecimal 不用 double。** 量取到 0.001 mL 是实验室的日常精度，
 * double 的二进制表示存不下 0.1，累加几十次之后台账就对不上账了。财务和库存这类
 * 「必须精确」的字段一律 BigDecimal。
 *
 * **二是不用 {@code @Version} 管库存扣减。** 这一条容易被写反，所以写在这里：
 * 版本号乐观锁的思路是「读出来、改、写回时比对版本，冲突就重试」——它需要**先读**，
 * 而扣减根本不需要读：{@code UPDATE ... WHERE available >= qty} 一条语句就能让数据库
 * 自己判断。多一次读不仅浪费，还会把冲突窗口拉长。
 * 版本号用在下游的申请表上，那里必须「读出来看状态、再改状态」。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "chemical_batch",
        indexes = @Index(name = "idx_batch_chemical", columnList = "chemicalId"))
public class ChemicalBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long chemicalId;

    /** 批次号，同一试剂下唯一 */
    @Column(nullable = false, length = 50)
    private String batchNo;

    /** 入库时的量，用于统计损耗；扣减不动它 */
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal initialQuantity;

    /** 当前可用量。出库扣的就是它，扣减走条件更新 */
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal availableQuantity;

    /** 存放位置，如「危化品柜 A-3」 */
    @Column(nullable = false, length = 100)
    private String location;

    /** 有效期。台账要能查出临期批次，这是危化品管理的规定动作 */
    @Column(nullable = false)
    private LocalDate expireDate;

    /**
     * 实际可用截止日。<b>出库过滤、库存视图、效期预警、报表只读这一列。</b>
     *
     * <ul>
     *   <li>入库时 = {@link #expireDate}（未开封，按包装有效期）</li>
     *   <li>开封后 = min(expireDate, 开封日 + {@link Chemical#getPostOpenDays()})</li>
     * </ul>
     *
     * 为什么不各处现算「expireDate 与开封日 + 天数取较小者」：那样同一套判定会被复制到
     * 出库、库存视图、预警、报表四处，迟早出现「库存视图说可用、出库说不可用」。
     * 物化成一列之后，口径只有一个。
     *
     * 允许 NULL 只是为了兼容迁移前的历史行（见 V4 脚本注释）；{@code @PrePersist} 会兜底。
     */
    @Column(name = "usable_until")
    private LocalDate usableUntil;

    /** 开封时间。为空表示未开封。一瓶只能开一次，重复开封会重置可用期，必须拦住 */
    @Column(name = "opened_at")
    private LocalDateTime openedAt;

    @Column(name = "opened_by", length = 100)
    private String openedBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public boolean isOpened() {
        return openedAt != null;
    }

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        // 兜底：任何绕过 StockService 的写入（测试、导入、手工 SQL）也不能留下 NULL，
        // 否则 `usable_until >= today` 对 NULL 恒为 unknown，那一批会从所有查询里消失
        if (usableUntil == null) {
            usableUntil = expireDate;
        }
    }
}
