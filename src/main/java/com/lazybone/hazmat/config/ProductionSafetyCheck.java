package com.lazybone.hazmat.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * prod profile 下的启动自检：配置成演示形态就不许启动。
 *
 * <p>为什么要在启动时失败，而不是打一行警告：演示账号（admin / ChangeMe123!）
 * 和种子数据一旦带上生产库，就是可被直接登录的后门。这类问题靠「记得改配置」
 * 不可靠，靠「配置没改就起不来」才可靠。
 *
 * <p>JWT 密钥长度由 {@code SecurityConfig} 校验；这里额外挡住「密钥还是开发默认值」
 * 这一种情况——长度够，但全世界的演示环境都知道它。
 */
@Component
public class ProductionSafetyCheck {

    /** 开发默认密钥的前缀；生产出现它等于没有签名 */
    private static final String DEV_SECRET_PREFIX = "hazmat-ledger-dev-secret";

    public ProductionSafetyCheck(Environment environment) {
        if (!Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            return;
        }

        List<String> problems = new ArrayList<>();
        if (environment.getProperty("hazmat.security.seed-users", Boolean.class, false)) {
            problems.add("hazmat.security.seed-users=true（会自动创建演示账号）");
        }
        if (environment.getProperty("hazmat.seed.enabled", Boolean.class, false)) {
            problems.add("hazmat.seed.enabled=true（会灌种子数据）");
        }
        String secret = environment.getProperty("hazmat.security.jwt-secret", "");
        if (secret.isBlank()) {
            problems.add("hazmat.security.jwt-secret 为空");
        } else if (secret.startsWith(DEV_SECRET_PREFIX)) {
            problems.add("hazmat.security.jwt-secret 仍是开发默认值");
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("prod profile 配置检查未通过："
                    + String.join("；", problems)
                    + "。请修正后再启动（示例见 .env.example）。");
        }
    }
}
