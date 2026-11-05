package com.lazybone.hazmat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazybone.hazmat.dto.ChemicalRequest;
import com.lazybone.hazmat.entity.AppUser;
import com.lazybone.hazmat.entity.UserRole;
import com.lazybone.hazmat.repository.AppUserRepository;
import com.lazybone.hazmat.repository.ChemicalRepository;
import com.lazybone.hazmat.service.ChemicalService;
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

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 台账与知识库的连接点：试剂详情 + 安全入口。
 *
 * 两类试剂必须分开验证——「有 MSDS 但这次没查到」与「压根没有这份 MSDS」
 * 在接口上是两个不同的结果（{@code available} 字段），不能都退化成空列表。
 *
 * 用独立 H2 库名，避免与其它 {@code @SpringBootTest} 上下文互相清表。
 * MSDS 块由启动钩子从 classpath 落库，本测试只负责建档与发请求。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:chemical-safety-it;DB_CLOSE_DELAY=-1",
        "hazmat.security.seed-users=false",
        "hazmat.ai.encode-on-startup=false"
})
@AutoConfigureMockMvc
class ChemicalSafetyTest {

    private static final String PASSWORD = "TestPass123!";
    private static final String METHANOL_CAS = "67-56-1";
    private static final String NO_MSDS_CAS = "CAS-TEST-999";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AppUserRepository userRepository;
    @Autowired private ChemicalRepository chemicalRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ChemicalService chemicalService;

    private Long methanolId;
    private Long noMsdsId;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        chemicalRepository.deleteAll();

        userRepository.save(AppUser.builder()
                .username("alice").displayName("张三").role(UserRole.USER)
                .passwordHash(passwordEncoder.encode(PASSWORD)).enabled(true).build());

        // 甲醇在 classpath 的 MSDS 目录里有对应文件
        methanolId = chemicalService.create(new ChemicalRequest(
                METHANOL_CAS, "甲醇", "易燃液体", "高", "mL", "AR 500mL", null, null)).getId();
        // 这个 CAS 没有对应的 MSDS 文件
        noMsdsId = chemicalService.create(new ChemicalRequest(
                NO_MSDS_CAS, "未收录试剂", "其他", "低", "g", "AR 100g", null, null)).getId();
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "alice", "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return "Bearer " + body.path("data").path("token").asText();
    }

    @Test
    @DisplayName("试剂详情：普通登录用户可按 id 取完整档案")
    void detailIsAvailableToLoggedInUser() throws Exception {
        mockMvc.perform(get("/api/chemicals/" + methanolId)
                        .header("Authorization", login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(methanolId))
                .andExpect(jsonPath("$.data.casNo").value(METHANOL_CAS))
                .andExpect(jsonPath("$.data.name").value("甲醇"));
    }

    @Test
    @DisplayName("安全入口：按试剂 CAS 限定检索，返回急救章节")
    void safetyReturnsSectionForIndexedChemical() throws Exception {
        mockMvc.perform(get("/api/chemicals/" + methanolId + "/safety")
                        .param("q", "甲醇 误食")
                        .header("Authorization", login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.casNo").value(METHANOL_CAS))
                .andExpect(jsonPath("$.data.chemicalName").value("甲醇"))
                .andExpect(jsonPath("$.data.hits").isNotEmpty())
                // CAS 过滤必须生效：不能混进别的试剂
                .andExpect(jsonPath("$.data.hits[*].casNo", everyItem(is(METHANOL_CAS))))
                .andExpect(jsonPath("$.data.hits[*].sectionNo", hasItem(4)));
    }

    @Test
    @DisplayName("没有收录 MSDS 的试剂：available=false，明确说没有资料而不是空列表")
    void safetyReportsUnavailableWhenNoMsdsExists() throws Exception {
        mockMvc.perform(get("/api/chemicals/" + noMsdsId + "/safety")
                        .param("q", "误食")
                        .header("Authorization", login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.hits").isEmpty())
                .andExpect(jsonPath("$.data.message").value(
                        "该试剂尚未索引 MSDS 原文，暂无法提供安全信息"));
    }

    @Test
    @DisplayName("安全入口要求登录：未带 token 返回 401")
    void safetyRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/chemicals/" + methanolId + "/safety")
                        .param("q", "误食"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("试剂不存在返回 404，而不是把空结果当成正常响应")
    void safetyReturns404ForUnknownChemical() throws Exception {
        mockMvc.perform(get("/api/chemicals/999999/safety")
                        .param("q", "误食")
                        .header("Authorization", login()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("查询为空返回 400：先报参数错误，不要走到检索")
    void safetyRejectsBlankQuery() throws Exception {
        mockMvc.perform(get("/api/chemicals/" + methanolId + "/safety")
                        .param("q", " ")
                        .header("Authorization", login()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
