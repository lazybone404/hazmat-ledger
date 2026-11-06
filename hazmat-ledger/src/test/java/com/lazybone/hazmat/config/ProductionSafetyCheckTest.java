package com.lazybone.hazmat.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 启动自检的纯单元测试：不启动 Spring，只模拟不同的 Environment 形态。
 *
 * <p>这几条规则的价值全在「拦住一次错误部署」，所以断言写成「会抛异常」而不是
 * 「记录了警告」——只有抛异常才真的拦住。
 */
class ProductionSafetyCheckTest {

    private static final String SAFE_SECRET = "a".repeat(48);

    @Test
    @DisplayName("prod 下开演示账号：拒绝启动")
    void rejectsSeededUsersInProd() {
        MockEnvironment env = prodEnvironment();
        env.setProperty("hazmat.security.seed-users", "true");

        assertThatThrownBy(() -> new ProductionSafetyCheck(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seed-users");
    }

    @Test
    @DisplayName("prod 下灌种子数据：拒绝启动")
    void rejectsSeedDataInProd() {
        MockEnvironment env = prodEnvironment();
        env.setProperty("hazmat.seed.enabled", "true");

        assertThatThrownBy(() -> new ProductionSafetyCheck(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hazmat.seed.enabled");
    }

    @Test
    @DisplayName("prod 下 JWT 仍是开发默认密钥：拒绝启动")
    void rejectsDevSecretInProd() {
        MockEnvironment env = prodEnvironment();
        env.setProperty("hazmat.security.jwt-secret",
                "hazmat-ledger-dev-secret-change-me-0123456789");

        assertThatThrownBy(() -> new ProductionSafetyCheck(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("开发默认值");
    }

    @Test
    @DisplayName("prod 配置正确：放行")
    void acceptsProperProdConfiguration() {
        assertThatCode(() -> new ProductionSafetyCheck(prodEnvironment()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("非 prod profile 不参与检查：本地开发照常用默认密钥")
    void developmentProfileIsNotChecked() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        env.setProperty("hazmat.security.seed-users", "true");
        env.setProperty("hazmat.seed.enabled", "true");

        assertThatCode(() -> new ProductionSafetyCheck(env)).doesNotThrowAnyException();
    }

    private static MockEnvironment prodEnvironment() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("hazmat.security.seed-users", "false");
        env.setProperty("hazmat.seed.enabled", "false");
        env.setProperty("hazmat.security.jwt-secret", SAFE_SECRET);
        return env;
    }
}
