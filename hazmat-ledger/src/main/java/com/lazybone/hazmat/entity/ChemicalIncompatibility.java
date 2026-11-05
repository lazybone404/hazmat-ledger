package com.lazybone.hazmat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 一条禁忌混放关系：{@code casA} 与 {@code casB} 不能存放在一起。
 *
 * <b>为什么用 CAS 号而不是 chemicalId：</b>禁忌是化学品本身的化学性质。
 * 台账里的某条档案被删掉、重建，或者同一 CAS 换了一条记录，禁忌关系都不该失效。
 * 关系表也因此可以独立于业务档案维护。
 *
 * <b>为什么存成一侧有序的二元组：</b>(A,B) 与 (B,A) 是同一条关系。
 * 存入时统一按 CAS 字符串排序，配合 {@code CHECK (cas_a < cas_b)} 与唯一索引，
 * 从结构上杜绝重复。查表时也只需要拼一个方向的键。
 *
 * <b>本表只记录「有依据的禁忌」，不记录「确认可以混放」。</b>
 * 查不到关系不等于安全，调用方必须把这种情况显示为「未覆盖」。
 * 依据统一放在 {@code reason}（危险原因）与 {@code source}（出处），
 * 当前种子数据全部来自仓库内 MSDS 第 10 节，不编造标准条款号。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "chemical_incompatibility",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_incompatibility_pair", columnNames = {"cas_a", "cas_b"}))
public class ChemicalIncompatibility {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cas_a", nullable = false, length = 20)
    private String casA;

    @Column(name = "cas_b", nullable = false, length = 20)
    private String casB;

    /** 危险原因，用一两句话说清混放会发生什么 */
    @Column(nullable = false, length = 500)
    private String reason;

    /** 依据出处，如「MSDS 7722-64-7 第10节 禁配物」 */
    @Column(nullable = false, length = 300)
    private String source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 建关系时统一排序。调用方可以按任意方向传入，
     * 落库与后续查表都用同一个规范顺序。
     */
    public static ChemicalIncompatibility of(String casA, String casB, String reason, String source) {
        String first = orderedFirst(casA, casB);
        String second = first.equals(casA) ? casB : casA;
        return ChemicalIncompatibility.builder()
                .casA(first)
                .casB(second)
                .reason(reason)
                .source(source)
                .build();
    }

    /** 两个不同 CAS 中字典序较小的那个；相同或为空属于调用方错误，直接拒绝 */
    public static String orderedFirst(String casA, String casB) {
        if (casA == null || casB == null) {
            throw new IllegalArgumentException("禁忌关系的两个 CAS 号都不能为空");
        }
        if (casA.equals(casB)) {
            throw new IllegalArgumentException("试剂不能与自己构成禁忌关系: " + casA);
        }
        return casA.compareTo(casB) <= 0 ? casA : casB;
    }

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        // 兜底：即使调用方用 builder 直接赋值，也不让顺序错误撞上 CHECK 约束。
        // 相等的情况不在这里处理——那是明确的调用错误，交给数据库约束报出来。
        if (casA != null && casB != null && casA.compareTo(casB) > 0) {
            String swap = casA;
            casA = casB;
            casB = swap;
        }
    }
}
