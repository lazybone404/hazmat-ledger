package com.lazybone.hazmat.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 安全配置：无状态 JWT + 方法级角色控制。
 *
 * 几个刻意的决定：
 * <ul>
 *   <li><b>不用 session。</b>接口本身就是产品，没有同源前端会话；无状态也让多实例部署时
 *       不需要共享登录态。停用/改密/改角色靠 {@code token_version} 撤销旧 token，
 *       校验带 30 秒本地缓存，多实例下最迟 30 秒生效。</li>
 *   <li><b>默认拒绝。</b>{@code anyRequest().authenticated()}，只放行登录、H2 控制台、
 *       错误页、探活与接口文档。新增接口忘了标注权限时是「要登录」，而不是「裸奔」。</li>
 *   <li><b>角色写进 token 的 roles claim</b>，不查库鉴权。每次请求都查库会让 JWT 失去
 *       无状态的意义；角色变更后等 token 过期或重新登录即可。</li>
 *   <li><b>密钥从配置读，长度不足直接启动失败</b>。HS256 的密钥太短等于没有签名，
 *       这类问题不该等到上线才靠人发现。</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** HS256 要求密钥至少 32 字节；短于这个长度直接拒绝启动 */
    private static final int MIN_SECRET_BYTES = 32;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtDecoder jwtDecoder,
                                           TokenVersionFilter tokenVersionFilter,
                                           RestAuthenticationEntryPoint entryPoint,
                                           RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                // 无状态 + Bearer token，CSRF 不适用；H2 控制台是 iframe，需要放开同源
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.frameOptions(f -> f.sameOrigin()))
                .authorizeHttpRequests(auth -> auth
                        // 注册接口本身公开，但是否开放由 AuthController 的配置开关决定；
                        // 关闭时返回 403，而不是让请求卡在 401 上分不清「没开」与「没登录」
                        .requestMatchers("/api/auth/login", "/api/auth/register",
                                "/h2-console/**", "/error").permitAll()
                        // 探活与版本信息给容器编排和监控用，不需要凭证；
                        // 只放行 health/info，actuator 的其它端点也没有暴露。
                        // health 用 /** 而不是精确匹配：liveness/readiness 是子路径，
                        // 写成精确匹配会让探针返回 401，容器就一直重启或摘流量。
                        // metrics/prometheus 给抓取器用，通常不带 token；
                        // 它们不含业务数据，生产环境仍应由网关限制来源。
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/actuator/info", "/actuator/prometheus")
                        .permitAll()
                        // 接口文档与 Swagger UI。可用 SPRINGDOC_ENABLED=false 整体关闭；
                        // 开放时它会把全部路由暴露出来，生产环境应由网关限制在内网
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // 演示前端是三个静态文件加本地 Vue 运行时，页面本身不含业务数据；
                        // 页面能公开打开，但页面里调用的 /api/** 仍然逐个要求 Bearer token。
                        // 不放开这些路径的话，浏览器打开首页会先撞上 401，连登录框都加载不出来。
                        .requestMatchers("/", "/index.html", "/app.js", "/styles.css",
                                "/vendor/**", "/favicon.ico", "/favicon.svg").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                        // 默认拒绝：新接口忘了配权限时至少要求登录
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                // 解码完成后再核对撤销版本；必须在 BearerTokenAuthenticationFilter 之后
                .addFilterAfter(tokenVersionFilter,
                        org.springframework.security.oauth2.server.resource.web.authentication
                                .BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 把 token 里的 {@code roles} 映射成 Spring Security 的 {@code ROLE_*} 权限。
     *
     * 默认转换器只认 {@code scope}/{@code scp}，不认自定义 claim；不写这一步，
     * {@code @PreAuthorize("hasRole('ADMIN')")} 永远不成立，而且报的是 403 不是配置错误，
     * 很容易误判成「权限规则写错了」。
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles == null) {
                return List.of();
            }
            return roles.stream()
                    .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
        });
        return converter;
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    public SecretKey jwtSecretKey(@Value("${hazmat.security.jwt-secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "hazmat.security.jwt-secret 至少需要 " + MIN_SECRET_BYTES + " 字节，当前 "
                            + bytes.length + " 字节；生产环境请用环境变量覆盖");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt 自带 salt 与成本因子，默认强度 10，适合交互式登录
        return new BCryptPasswordEncoder();
    }
}
