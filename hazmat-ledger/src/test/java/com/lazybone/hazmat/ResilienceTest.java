package com.lazybone.hazmat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazybone.hazmat.dto.MsdsSearchResult;
import com.lazybone.hazmat.entity.AppUser;
import com.lazybone.hazmat.entity.UserRole;
import com.lazybone.hazmat.msds.MsdsSearchService;
import com.lazybone.hazmat.msds.SearchConcurrencyLimiter;
import com.lazybone.hazmat.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 过载兜底的行为测试：登录失败锁定、探针匿名可达、检索并发闸门。
 *
 * 这些行为都不是「正常情况下会走到」的分支，所以不能靠手工点一遍来确认；
 * 每条测试都在主动制造它们的前置条件（连续失败、占满许可）。
 *
 * 用独立 H2 库名，避免与其它 {@code @SpringBootTest} 上下文互相清表。
 * 检索的等待时间设为 0：测试要的是「拿不到许可就立刻降级」，
 * 等 200ms 只会让整套测试变慢，不改变任何断言。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:resilience-it;DB_CLOSE_DELAY=-1",
        "hazmat.security.seed-users=false",
        "hazmat.ai.encode-on-startup=false",
        "hazmat.msds.search.max-concurrent=1",
        "hazmat.msds.search.wait-millis=0",
        "hazmat.security.login-lock=5m"
})
@AutoConfigureMockMvc
class ResilienceTest {

    private static final String PASSWORD = "TestPass123!";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AppUserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SearchConcurrencyLimiter limiter;
    @Autowired private MsdsSearchService searchService;

    /**
     * 每个测试方法用一个新用户名。
     *
     * {@code LoginAttemptGuard} 是单例，计数存在内存里、不会随 {@code deleteAll} 清空：
     * 复用同一个用户名时，前一个测试方法造成的锁定会泄漏到下一个方法里，
     * 表现为「明明先成功登录过一次，后面还是 401」这种和被测逻辑无关的失败。
     * 换名字是成本最低的隔离方式——比给生产代码加一个 reset 方法干净。
     */
    private String username;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        username = "user-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        createUser(username);
    }

    private void createUser(String name) {
        userRepository.save(AppUser.builder()
                .username(name).displayName("测试用户").role(UserRole.USER)
                .passwordHash(passwordEncoder.encode(PASSWORD)).enabled(true).build());
    }

    private MvcResult attemptLogin(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", password))))
                .andReturn();
    }

    @Test
    @DisplayName("连续失败达到阈值后锁定：锁定期内即使密码正确也拒绝")
    void lockAfterRepeatedFailures() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(attemptLogin(username, "wrong-password").getResponse().getStatus())
                    .as("第 %d 次错误密码", i + 1)
                    .isEqualTo(401);
        }

        // 第 6 次用正确密码：仍然 401，且换成「尝试次数过多」——
        // 锁定期内必须挡住密码校验，否则爆破者只要密码碰对就能进
        MvcResult locked = attemptLogin(username, PASSWORD);
        assertThat(locked.getResponse().getStatus()).isEqualTo(401);
        JsonNode body = objectMapper.readTree(
                locked.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("message").asText()).contains("尝试次数过多");
    }

    @Test
    @DisplayName("没到阈值前登录成功会清零计数，不会因为历史失败被误锁")
    void successResetsFailureCounter() throws Exception {
        for (int i = 0; i < 4; i++) {
            assertThat(attemptLogin(username, "wrong-password").getResponse().getStatus())
                    .isEqualTo(401);
        }

        assertThat(attemptLogin(username, PASSWORD).getResponse().getStatus()).isEqualTo(200);

        // 清零后重新计数：再错 4 次依然不锁
        for (int i = 0; i < 4; i++) {
            assertThat(attemptLogin(username, "wrong-password").getResponse().getStatus())
                    .isEqualTo(401);
        }
        assertThat(attemptLogin(username, PASSWORD).getResponse().getStatus())
                .as("成功登录已清零一次计数，之后 4 次失败不足以触发锁定")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("锁定按用户名隔离：一个账号被锁不影响另一个账号")
    void lockIsPerUsername() throws Exception {
        String other = "user-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        createUser(other);

        for (int i = 0; i < 5; i++) {
            attemptLogin(username, "wrong-password");
        }

        assertThat(attemptLogin(username, PASSWORD).getResponse().getStatus()).isEqualTo(401);
        assertThat(attemptLogin(other, PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("liveness / readiness 探针可匿名访问，未登录也能拿到 200")
    void probesAreAnonymouslyReachable() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("检索许可被占满时返回降级结果，而不是排队或抛异常")
    void searchDegradesWhenPermitsExhausted() {
        assertThat(limiter.tryAcquire()).as("测试前置：先占满唯一的许可").isTrue();
        try {
            MsdsSearchResult result = searchService.search("甲醇误食怎么办", null, 5);

            assertThat(result.degraded()).isTrue();
            assertThat(result.degradedReason()).contains("并发已满");
            assertThat(result.hits()).isEmpty();
        } finally {
            limiter.release();
        }
    }

    @Test
    @DisplayName("许可释放后检索恢复，不会因为一次高并发永久降级")
    void searchRecoversAfterRelease() {
        // 前面的占用与释放成对出现，这里验证许可确实回到了池子里：
        // 如果 finally 漏写，这个断言会失败，而不是等到线上才发现检索一直降级
        assertThat(limiter.tryAcquire()).isTrue();
        limiter.release();

        MsdsSearchResult result = searchService.search("甲醇", null, 5);
        assertThat(result.degradedReason()).doesNotContain("并发已满");
    }
}
