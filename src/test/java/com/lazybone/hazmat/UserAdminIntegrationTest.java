package com.lazybone.hazmat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazybone.hazmat.entity.AppUser;
import com.lazybone.hazmat.entity.UserRole;
import com.lazybone.hazmat.repository.AppUserRepository;
import com.lazybone.hazmat.security.TokenVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 账号管理的端到端测试：JWT 撤销、最后一个管理员保护、Prometheus 端点。
 *
 * <p>撤销相关的断言必须走真实过滤器链：直接调 {@code TokenVersionService} 只能证明
 * 「版本号加一了」，证明不了「旧 token 被拒」——中间还差签名、解码、过滤器顺序三道关。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:user-admin-it;DB_CLOSE_DELAY=-1",
        "hazmat.security.seed-users=false",
        "hazmat.ai.encode-on-startup=false"
})
/**
 * 默认情况下 Spring Boot 的测试支持会**关掉指标导出**（只在 JVM 内用 SimpleMeterRegistry），
 * 表现是 {@code /actuator/prometheus} 404、而 health 正常——非常容易误判成端点没暴露。
 * 这里显式打开 metrics，验证的是真实运行时形态；只关 tracing，避免引入本测试不需要的链路依赖。
 */
@AutoConfigureObservability(metrics = true, tracing = false)
@AutoConfigureMockMvc
class UserAdminIntegrationTest {

    private static final String PASSWORD = "TestPass123!";
    private static final String NEW_PASSWORD = "NewPass456!";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AppUserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TokenVersionService tokenVersions;

    private Long adminId;
    private Long aliceId;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        // 撤销版本缓存是单例、跨测试保留的：上一个用例把 alice 停用后缓存里是「不存在」，
        // 这里重建了 alice 但缓存还不知道。不 evict 会让后面的用例莫名 401。
        for (String username : new String[]{"admin", "admin2", "alice"}) {
            tokenVersions.evict(username);
        }

        adminId = createUser("admin", "系统管理员", UserRole.ADMIN).getId();
        aliceId = createUser("alice", "张三", UserRole.USER).getId();
    }

    private AppUser createUser(String username, String displayName, UserRole role) {
        return userRepository.save(AppUser.builder()
                .username(username)
                .displayName(displayName)
                .role(role)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .enabled(true)
                .build());
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return body.path("data").path("token").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    @DisplayName("停用账号后，已签发的 token 立刻 401")
    void disablingUserRevokesExistingToken() throws Exception {
        String aliceToken = login("alice", PASSWORD);
        String adminToken = login("admin", PASSWORD);

        mockMvc.perform(patch("/api/users/" + aliceId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));

        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("本人改密后旧 token 失效，新密码能重新登录")
    void changingOwnPasswordRevokesOldToken() throws Exception {
        String aliceToken = login("alice", PASSWORD);

        mockMvc.perform(post("/api/auth/password")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "oldPassword", PASSWORD,
                                "newPassword", NEW_PASSWORD))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isUnauthorized());

        String fresh = login("alice", NEW_PASSWORD);
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(fresh)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("alice"));
    }

    @Test
    @DisplayName("logout-all 让所有设备上的 token 失效")
    void logoutAllRevokesEveryToken() throws Exception {
        String first = login("alice", PASSWORD);
        String second = login("alice", PASSWORD);

        mockMvc.perform(post("/api/auth/logout-all").header("Authorization", bearer(first)))
                .andExpect(status().isOk());

        for (String token : new String[]{first, second}) {
            mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(token)))
                    .andExpect(status().isUnauthorized());
        }
        // 重新登录拿到的 token 带新版本，可以继续用
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(login("alice", PASSWORD))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("账号管理限 ADMIN：普通用户 403，管理员能拿到列表")
    void userAdministrationRequiresAdmin() throws Exception {
        mockMvc.perform(get("/api/users").header("Authorization", bearer(login("alice", PASSWORD))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(get("/api/users").header("Authorization", bearer(login("admin", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("不能停用或降级最后一个管理员；有第二个管理员后就能")
    void lastEnabledAdminIsProtected() throws Exception {
        String adminToken = login("admin", PASSWORD);

        mockMvc.perform(patch("/api/users/" + adminId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", false))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("不能停用或降级最后一个启用中的管理员"));

        mockMvc.perform(patch("/api/users/" + adminId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("role", "USER"))))
                .andExpect(status().isBadRequest());

        // 补一个管理员之后，原管理员可以被停用——守卫只拦「最后一个」，不是禁止改管理员
        createUser("admin2", "管理员乙", UserRole.ADMIN);
        mockMvc.perform(patch("/api/users/" + adminId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    @Test
    @DisplayName("管理员重置密码后对方旧 token 失效")
    void adminResetPasswordRevokesTargetToken() throws Exception {
        String aliceToken = login("alice", PASSWORD);
        String adminToken = login("admin", PASSWORD);

        mockMvc.perform(post("/api/users/" + aliceId + "/password")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("newPassword", NEW_PASSWORD))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isUnauthorized());
        assertThat(login("alice", NEW_PASSWORD)).isNotBlank();
    }

    @Test
    @DisplayName("Prometheus 端点可匿名抓取，且检索后能读到检索耗时指标")
    void prometheusExposesSearchMetrics() throws Exception {
        // timer 是懒注册的：没有发生过检索时，指标名根本不存在
        mockMvc.perform(get("/api/msds/search").param("q", "甲醇 泄漏")
                        .header("Authorization", bearer(login("alice", PASSWORD))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("msds_search_seconds_count")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("degraded")));
    }
}
