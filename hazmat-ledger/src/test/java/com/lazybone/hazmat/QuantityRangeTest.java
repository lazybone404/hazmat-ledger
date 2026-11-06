package com.lazybone.hazmat;

import com.lazybone.hazmat.dto.InboundRequest;
import com.lazybone.hazmat.dto.RequisitionRequest;
import com.lazybone.hazmat.dto.ScrapRequest;
import com.lazybone.hazmat.dto.StocktakeItemRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数量字段的上界校验。
 *
 * <p>背景：写路径压测用 {@code quantity=1000000000} 打领用申请，拿到的是 500，
 * 而不是 400。原因是库表是 {@code DECIMAL(12,3)}，最大只能存 999999999.999，
 * 超出后 Hibernate 在提交时报 {@code Data truncation: Out of range value}。
 * 参数错误被记成服务端错误，调用方分不清「我传错了」和「服务挂了」，
 * 指标上也会多出一批假的 5xx。
 *
 * <p>这里直接校验 DTO 注解：HTTP 层把校验失败映射成 400 的链路已有其它测试覆盖，
 * 本用例守的是「四个入口的数量都不许超过库表精度」这条约束本身。
 */
class QuantityRangeTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    /** DECIMAL(12,3)：9 位整数 + 3 位小数 */
    private static final BigDecimal MAX = new BigDecimal("999999999.999");
    private static final BigDecimal OVER = new BigDecimal("1000000000");

    @Test
    @DisplayName("入库量超过 DECIMAL(12,3) 上限时是校验错误，不是数据库异常")
    void inboundQuantityOverRangeIsRejected() {
        InboundRequest request = new InboundRequest("B-1", OVER, "柜 A", LocalDate.now().plusYears(1));

        assertThat(messages(request)).containsExactly("入库量超出可记录范围");
    }

    @Test
    @DisplayName("领用量、报废量、实盘量超过上限同样在校验层被挡下")
    void otherQuantitiesOverRangeAreRejected() {
        assertThat(messages(new RequisitionRequest(1L, OVER, "实验"))).contains("领用量超出可记录范围");
        assertThat(messages(new ScrapRequest(OVER, "过期"))).contains("报废量超出可记录范围");
        assertThat(messages(new StocktakeItemRequest(OVER, null))).contains("实盘量超出可记录范围");
    }

    @Test
    @DisplayName("上界本身是合法的，不会被误杀")
    void upperBoundItselfIsAccepted() {
        assertThat(messages(new InboundRequest("B-1", MAX, "柜 A", LocalDate.now().plusYears(1)))).isEmpty();
        assertThat(messages(new RequisitionRequest(1L, MAX, "实验"))).isEmpty();
        assertThat(messages(new ScrapRequest(MAX, "过期"))).isEmpty();
        assertThat(messages(new StocktakeItemRequest(MAX, null))).isEmpty();
    }

    private Set<String> messages(Object request) {
        return VALIDATOR.validate(request).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }
}
