package com.lazybone.hazmat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazybone.hazmat.entity.AppUser;
import com.lazybone.hazmat.entity.UserRole;
import com.lazybone.hazmat.repository.AppUserRepository;
import com.lazybone.hazmat.security.TokenVersionService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 自助注册：开关打开时的完整路径。
 *
 * <p>开关关闭时返回 403 的用例在 {@link SecurityIntegrationTest}（那个上下文默认关闭）。
 *
 * <p>这里验证的是设计意图：注册只产生「待启用账号」，能不能登录仍由管理员决定。
 * 如果注册完就能直接登录，这个接口就变成了任何匿名调用方都能拿到系统入口的通道。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:registration-it;DB_CLOSE_DELAY=-1",
        "hazmat.security.seed-users=false",
        "hazmat.ai.encode-on-startup=false",
        "hazmat.security.registration.enabled=true"
})
@AutoConfigureMockMvc
class RegistrationIntegrationTest {

    private static final String ADMIN_PASSWORD = "AdminPass123!";
    private static final String NEW_PASSWORD = "NewUserPass123!";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AppUserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TokenVersionService tokenVersions;

    private Long adminId;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        // 撤销版本缓存是单例；上一个用例留下的「用户不存在」记录会让新账号登录莫名 401
        tokenVersions.evict("newcomer");
        tokenVersions.evict("admin");
        adminId = userRepository.save(AppUser.builder()
                .username("admin").displayName("系统管理员").role(UserRole.ADMIN)
                .passwordHash(passwordEncoder.encode(ADMIN_PASSWORD)).enabled(true).build()).getId();
    }

    private void register(String username, String displayName, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "displayName", displayName, "password", password))))
                .andExpect(status().isOk());
    }

    /** 登录成功返回 token；失败时断言状态码 */
    private String login(String username, String password, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", password))))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        if (expectedStatus != 200) {
            return null;
        }
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("token").asText();
    }

    @Test
    @DisplayName("注册成功但账号待启用：能落库、角色是 USER，登录被拒")
    void registeredAccountIsCreatedDisabled() throws Exception {
        register("newcomer", "新同事", NEW_PASSWORD);

        AppUser user = userRepository.findByUsername("newcomer").orElseThrow();
        assertThat(user.isEnabled()).isFalse();
        assertThat(user.getRole()).isEqualTo(UserRole.USER);

        login("newcomer", NEW_PASSWORD, 401);
    }

    @Test
    @DisplayName("管理员启用后即可登录，且拿到的是普通用户身份")
    void adminEnablesAccountThenItCanLogIn() throws Exception {
        register("newcomer", "新同事", NEW_PASSWORD);
        String adminToken = login("admin", ADMIN_PASSWORD, 200);

        mockMvc.perform(patch("/api/users/" + userRepository.findByUsername("newcomer").orElseThrow().getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));

        String token = login("newcomer", NEW_PASSWORD, 200);
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("newcomer"))
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    @DisplayName("用户名已存在时返回同样的成功，不覆盖原账号密码，也不暴露账号是否存在")
    void duplicateUsernameLooksLikeSuccess() throws Exception {
        register("newcomer", "新同事", NEW_PASSWORD);

        // 第二次用同一个用户名、不同密码、不同显示名
        register("newcomer", "冒充者", "AnotherPass123!");

        assertThat(userRepository.findAll().stream()
                .filter(u -> u.getUsername().equals("newcomer")).count()).isEqualTo(1);
        AppUser user = userRepository.findByUsername("newcomer").orElseThrow();
        assertThat(user.getDisplayName()).isEqualTo("新同事");
        assertThat(passwordEncoder.matches(NEW_PASSWORD, user.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("AnotherPass123!", user.getPasswordHash())).isFalse();
    }

    @Test
    @DisplayName("参数校验在落库前生效：用户名过短或密码过短返回 400")
    void invalidRegistrationIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "ab", "displayName", "太短", "password", NEW_PASSWORD))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "newcomer", "displayName", "新同事", "password", "short"))))
                .andExpect(status().isBadRequest());
        assertThat(userRepository.findByUsername("newcomer")).isEmpty();
    }
}
