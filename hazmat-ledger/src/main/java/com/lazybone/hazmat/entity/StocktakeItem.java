package com.lazybone.hazmat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 盘点明细：一个批次一条。
 *
 * <b>为什么按批次而不是按试剂：</b>库存记在批次上（{@link ChemicalBatch#getAvailableQuantity()}），
 * 「这瓶甲醇还有多少」才是能被数出来的东西。按试剂盘只能得到一个总数，
 * 总数对得上不代表批次对得上，差异也落不到具体的哪一瓶上。
 *
 * <b>{@code bookQuantity} 是快照，不是实时值。</b>实盘量的语义是「相对于我数的那一刻」，
 * 所以账面量必须在建单时就冻结。确认时用条件更新
 * {@code WHERE available_quantity = bookQuantity}：对不上说明盘点期间有人出库/入库/报废，
 * 这张单已经过期，必须重新盘，而不是拿一个错误的基准去调账。
 *
 * <b>{@code batchNo} 也是快照。</b>批次号不可改，存一份是为了盘点单脱离批次表也能读——
 * 台账要回答的是「当时盘的是哪一瓶」，不依赖别处数据是否还在。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "stocktake_item",
        indexes = {
                @Index(name = "idx_stocktake_item_stocktake", columnList = "stocktakeId"),
                @Index(name = "idx_stocktake_item_batch", columnList = "batchId")
        })
public class StocktakeItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long stocktakeId;

    @Column(nullable = false)
    private Long batchId;

    @Column(nullable = false)
    private Long chemicalId;

    @Column(nullable = false, length = 50)
    private String batchNo;

    /** 建单那一刻的账面可用量，冻结 */
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal bookQuantity;

    /** 实盘量。DRAFT 期间可以反复改，提交后不允许再改 */
    @Column(precision = 12, scale = 3)
    private BigDecimal actualQuantity;

    /** 差异 = 实盘 - 账面。提交时算好存下来，列表页不用再算一遍 */
    @Column(precision = 12, scale = 3)
    private BigDecimal diffQuantity;

    /** 差异说明，比如「倾倒损失未登记」 */
    @Column(length = 500)
    private String note;
}
