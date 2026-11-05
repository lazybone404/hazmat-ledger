package com.lazybone.hazmat.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 禁忌关系的入库规范：一对试剂只能有一条规则，与书写方向无关。
 *
 * 这层不依赖 Spring，是因为它守的是数据库唯一约束与 CHECK 约束之前的那道门：
 * 如果调用方可以按任意方向建两条，唯一索引就形同虚设。
 */
class ChemicalIncompatibilityTest {

    @Test
    @DisplayName("正反序传入都归一化成 CAS 字典序，查表只需一个方向")
    void normalizesPairOrder() {
        ChemicalIncompatibility forward = ChemicalIncompatibility.of(
                "7722-64-7", "7647-01-0", "高锰酸钾禁配浓盐酸", "MSDS 7722-64-7 第10节");
        ChemicalIncompatibility backward = ChemicalIncompatibility.of(
                "7647-01-0", "7722-64-7", "高锰酸钾禁配浓盐酸", "MSDS 7722-64-7 第10节");

        assertThat(forward.getCasA()).isEqualTo("7647-01-0");
        assertThat(forward.getCasB()).isEqualTo("7722-64-7");
        assertThat(backward.getCasA()).isEqualTo(forward.getCasA());
        assertThat(backward.getCasB()).isEqualTo(forward.getCasB());
    }

    @Test
    @DisplayName("试剂不能与自己构成禁忌关系")
    void rejectsSelfPair() {
        assertThatThrownBy(() -> ChemicalIncompatibility.of(
                "67-56-1", "67-56-1", "自身", "测试"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("自己");
    }

    @Test
    @DisplayName("缺少任一 CAS 直接拒绝，不把空值放进关系表")
    void rejectsNullCas() {
        assertThatThrownBy(() -> ChemicalIncompatibility.of(
                null, "67-56-1", "空值", "测试"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
