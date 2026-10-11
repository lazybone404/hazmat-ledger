package com.lazybone.hazmat.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存流水 —— 每一次库存变动都留一条，带变动后的余额。
 *
 * 为什么明细表要存 {@code balanceAfter}（这是容易被问到的点）：
 *
 * 只存变动量的话，想查「某时刻的库存」得把之前所有记录累加一遍。数据一多就慢，
 * 而且**累加依赖前面的记录一条不缺**——万一有漏记或重复，余额会一直错下去，
 * 还很难发现。存下每笔的余额，对账就变成了「拿流水最后一笔的余额和批次表的
 * availableQuantity 比一比」，不一致立刻能查出来。
 *
 * 代价是它是冗余字段，所以它必须和扣减**在同一个事务里**写——
 * 见 StockService 里扣减与记流水为什么绑在一起。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "stock_ledger",
        indexes = {
                @Index(name = "idx_ledger_batch", columnList = "batchId"),
                @Index(name = "idx_ledger_ref", columnList = "refType,refId")
        })
public class StockLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long batchId;

    @Column(nullable = false)
    private Long chemicalId;

    /** IN 入库 / OUT 出库 / SCRAP 报废 / ADJUST 盘点调整 */
    @Column(nullable = false, length = 10)
    private String changeType;

    /** 变动量，入库为正、出库为负，带符号存 */
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    /** 变动之后的余额，冗余字段，用途见类注释 */
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal balanceAfter;

    /** 引起这次变动的业务类型：INBOUND / REQUISITION / SCRAP / ADJUST */
    @Column(nullable = false, length = 20)
    private String refType;

    /** 引起变动的业务 id */
    private Long refId;

    @Column(nullable = false, length = 100)
    private String operator;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
