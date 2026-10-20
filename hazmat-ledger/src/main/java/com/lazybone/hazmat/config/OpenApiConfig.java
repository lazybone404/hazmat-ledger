package com.lazybone.hazmat.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档配置。
 *
 * 这里只做两件事：写清楚这是哪套接口，以及声明 Bearer JWT 这种认证方式。
 * 后者是必要的——springdoc 不会从 Spring Security 的配置里推断出「要带 token」，
 * 不声明的话 Swagger UI 上没有 Authorize 按钮，只能手工拼 curl。
 *
 * 接口清单本身由 Controller 上的注解自动生成，不在这里重复维护；
 * 需要补充说明时用 {@code @Operation} / {@code @Parameter} 标在对应方法上。
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI hazmatOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("危化品台账 API")
                        .version("v1")
                        .description("""
                                高校实验室危化品领用与台账：试剂建档、批次入库、领用审批、出库核销、报废处置，
                                以及 MSDS 安全信息检索。

                                除 `/api/auth/login`、探活与文档外，所有接口都需要 Bearer token：
                                先调用登录接口拿到 `data.token`，再点右上角 Authorize 填入。
                                身份与角色只从 token 读取，请求体里的申请人、审批人、操作人字段均不生效。
                                """))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录接口返回的 token，不需要自己加 Bearer 前缀")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
