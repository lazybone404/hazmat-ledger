package com.lazybone.hazmat.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 试剂档案 —— 一种化学品一条，对应 chemical 表。
 *
 * 档案和批次是两张表，这点值得单独说：**同一个试剂会分多次采购、每批效期不同**，
 * 台账要能按批追溯到具体那一瓶。把库存直接记在档案上（很多 CRUD 项目是这么做的）
 * 就没法回答「过期的具体是哪一批」。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "chemical")
public class Chemical {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** CAS 号，化学品的国际唯一标识。业务上唯一，用来防重复建档 */
    @Column(nullable = false, unique = true, length = 20)
    private String casNo;

    @Column(nullable = false, length = 200)
    private String name;

    /** 危险类别：易燃 / 腐蚀 / 有毒 / 易制爆 / 一般 */
    @Column(nullable = false, length = 50)
    private String category;

    /**
     * 危险等级：低 / 中 / 高。**高等级走双人复核**（见 {@link RequisitionStatus#PENDING_SECOND}）。
     *
     * 「高」这个取值被流程引用了，所以提成常量放在字段旁边。真实系统里这一列应当是
     * 字典表或枚举（现在是个自由字符串，谁都能写「很高」），那是建档入口该补的校验；
     * 这里只把流程要用的那个取值钉住，避免同一个字面量散在 Service 里。
     */
    @Column(nullable = false, length = 20)
    private String hazardLevel;

    /** 「高危」的取值。流程靠它判断要不要双人复核 */
    public static final String HAZARD_HIGH = "高";

    /** 这份档案是不是高危 —— 判断收在实体里，免得每个调用方各写一遍 equals */
    public boolean isHighHazard() {
        return HAZARD_HIGH.equals(hazardLevel);
    }

    /** 计量单位：mL / g / 瓶 */
    @Column(nullable = false, length = 20)
    private String unit;

    /** 规格，如「AR 500mL」 */
    @Column(length = 100)
    private String spec;

    /** MSDS 文件地址。真实系统里这是必填项，台账要能一键调出安全说明书 */
    @Column(length = 300)
    private String msdsUrl;

    /**
     * 开封后的可用天数（如甲醇 90 天）。为空表示不做开封后追踪。
     *
     * <b>为什么是档案属性而不是批次属性：</b>它是化学性质——同一试剂的每一批开封后
     * 稳定性都一样。放在档案上，改一处对所有批次生效；放在批次上，每次入库都要抄一遍，
     * 抄错一次就有一个批次按错误期限判定。
     *
     * <b>为什么需要它：</b>批次上的 {@code expireDate} 是供应商标注的包装有效期，
     * 前提是未开封。试剂开封后稳定性由开封那一刻重新起算，只看包装有效期会把
     * 已经变质的批次当成「还有半年」。
     */
    @Column(name = "post_open_days")
    private Integer postOpenDays;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
