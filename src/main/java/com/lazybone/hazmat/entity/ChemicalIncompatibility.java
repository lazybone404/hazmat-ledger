package com.lazybone.hazmat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    /** 依据出处，如「MSDS 7722-64-7 第10节 禁配物」——人读的摘要，结构化依据在下面 */
    @Column(nullable = false, length = 300)
    private String source;

    /** 结构化依据来源：MSDS / GB。程序化创建默认 MSDS，避免 NOT NULL 违约 */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 16)
    private IncompatibilitySourceType sourceType;

    /** 依据文档标识，如「MSDS 7722-64-7 / MSDS 7647-01-0」 */
    @Column(name = "source_doc", length = 200)
    private String sourceDoc;

    /** 条款位置，如「第10节 稳定性和反应性·禁配物」 */
    @Column(name = "source_clause", length = 64)
    private String sourceClause;

    /** 依据原文摘录，用于前端直接展示，不需要再打开 MSDS */
    @Column(name = "source_excerpt", length = 1000)
    private String sourceExcerpt;

    /** 核实状态：VERIFIED 才算有依据；PENDING 是「有规则但未核实」 */
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 16)
    private VerificationStatus verificationStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 建关系时统一排序。调用方可以按任意方向传入，
     * 落库与后续查表都用同一个规范顺序。
     */
    public static ChemicalIncompatibility of(String casA, String casB, String reason, String source) {
        return of(casA, casB, reason, source,
                IncompatibilitySourceType.MSDS, null, null, null, VerificationStatus.PENDING);
    }

    /** 带结构化依据的创建入口：迁移、种子与批量导入都应该走这条 */
    public static ChemicalIncompatibility of(String casA, String casB, String reason, String source,
                                             IncompatibilitySourceType sourceType,
                                             String sourceDoc,
                                             String sourceClause,
                                             String sourceExcerpt,
                                             VerificationStatus verificationStatus) {
        String first = orderedFirst(casA, casB);
        String second = first.equals(casA) ? casB : casA;
        ChemicalIncompatibility rule = ChemicalIncompatibility.builder()
                .casA(first)
                .casB(second)
                .reason(reason)
                .source(source)
                .sourceType(sourceType == null ? IncompatibilitySourceType.MSDS : sourceType)
                .sourceDoc(sourceDoc)
                .sourceClause(sourceClause)
                .sourceExcerpt(sourceExcerpt)
                .verificationStatus(verificationStatus == null ? VerificationStatus.PENDING : verificationStatus)
                .build();
        return rule;
    }

    /**
     * 补齐已存在规则的结构化依据。
     *
     * <p>只在字段为空时填，不覆盖人工修正过的内容；状态也只在当前为 PENDING 时升级，
     * 避免一次种子灌入把「已降级为待核实」的规则又改回 VERIFIED。
     */
    public void applyBasis(IncompatibilitySourceType type,
                           String doc,
                           String clause,
                           String excerpt,
                           VerificationStatus status) {
        if (sourceType == null) {
            sourceType = type == null ? IncompatibilitySourceType.MSDS : type;
        }
        if (sourceDoc == null) {
            sourceDoc = doc;
        }
        if (sourceClause == null) {
            sourceClause = clause;
        }
        if (sourceExcerpt == null) {
            sourceExcerpt = excerpt;
        }
        if (status == VerificationStatus.VERIFIED && verificationStatus != VerificationStatus.VERIFIED) {
            verificationStatus = VerificationStatus.VERIFIED;
        }
    }

    /** 是否具备可展示的结构化依据（doc + clause + excerpt 至少有一个，且状态已核实） */
    public boolean hasVerifiedBasis() {
        return verificationStatus == VerificationStatus.VERIFIED
                && (sourceDoc != null || sourceClause != null || sourceExcerpt != null);
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
