package com.lazybone.hazmat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazybone.hazmat.dto.ChemicalRequest;
import com.lazybone.hazmat.dto.InboundRequest;
import com.lazybone.hazmat.dto.RequisitionRequest;
import com.lazybone.hazmat.entity.AppUser;
import com.lazybone.hazmat.entity.UserRole;
import com.lazybone.hazmat.repository.AppUserRepository;
import com.lazybone.hazmat.repository.ChemicalBatchRepository;
import com.lazybone.hazmat.repository.ChemicalRepository;
import com.lazybone.hazmat.repository.RequisitionLogRepository;
import com.lazybone.hazmat.repository.RequisitionRepository;
import com.lazybone.hazmat.repository.StockLedgerRepository;
import com.lazybone.hazmat.service.ChemicalService;
import com.lazybone.hazmat.service.RequisitionService;
import com.lazybone.hazmat.service.StockService;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
    @Autowired private ChemicalBatchRepository batchRepository;
    @Autowired private RequisitionRepository requisitionRepository;
    @Autowired private RequisitionLogRepository requisitionLogRepository;
    @Autowired private StockLedgerRepository ledgerRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ChemicalService chemicalService;
    @Autowired private StockService stockService;
    @Autowired private RequisitionService requisitionService;

    private Long methanolId;
    private Long noMsdsId;
    private String methanolReqNo;

    @BeforeEach
    void setUp() {
        requisitionLogRepository.deleteAll();
        requisitionRepository.deleteAll();
        ledgerRepository.deleteAll();
        batchRepository.deleteAll();
        userRepository.deleteAll();
        chemicalRepository.deleteAll();

        userRepository.save(AppUser.builder()
                .username("alice").displayName("张三").role(UserRole.USER)
                .passwordHash(passwordEncoder.encode(PASSWORD)).enabled(true).build());
        userRepository.save(AppUser.builder()
                .username("bob").displayName("李四").role(UserRole.USER)
                .passwordHash(passwordEncoder.encode(PASSWORD)).enabled(true).build());

        // 甲醇在 classpath 的 MSDS 目录里有对应文件
        methanolId = chemicalService.create(new ChemicalRequest(
                METHANOL_CAS, "甲醇", "易燃液体", "高", "mL", "AR 500mL", null, null)).getId();
        // 这个 CAS 没有对应的 MSDS 文件
        noMsdsId = chemicalService.create(new ChemicalRequest(
                NO_MSDS_CAS, "未收录试剂", "其他", "低", "g", "AR 100g", null, null)).getId();

        // 安全摘要挂在领用申请下面，所以两条流程都要先有库存和申请单
        stockService.inbound(methanolId,
                new InboundRequest("CS-1", new BigDecimal("500"), "柜 A-1",
                        LocalDate.now().plusYears(1)), "alice");
        methanolReqNo = requisitionService.submit("alice", new RequisitionRequest(
                methanolId, new BigDecimal("10"), "安全摘要测试")).getReqNo();
        stockService.inbound(noMsdsId,
                new InboundRequest("CS-2", new BigDecimal("100"), "柜 A-2",
                        LocalDate.now().plusYears(1)), "alice");
        requisitionService.submit("alice", new RequisitionRequest(
                noMsdsId, new BigDecimal("10"), "安全摘要测试"));
    }

    private String login() throws Exception {
        return login("alice");
    }

    private String login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", PASSWORD))))
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

    @Test
    @DisplayName("领用详情的安全摘要：按固定节号返回危险性、急救、泄漏、防护四节")
    void requisitionSafetyOverviewReturnsFixedSections() throws Exception {
        mockMvc.perform(get("/api/requisitions/" + methanolReqNo + "/safety")
                        .header("Authorization", login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.casNo").value(METHANOL_CAS))
                .andExpect(jsonPath("$.data.sections").isNotEmpty())
                // 每节正文都要有内容；「取到了节但正文是空」对领用人等于没给
                .andExpect(jsonPath("$.data.sections[*].sectionNo", hasItem(2)))
                .andExpect(jsonPath("$.data.sections[*].sectionNo", hasItem(4)))
                .andExpect(jsonPath("$.data.sections[*].title").isNotEmpty())
                .andExpect(jsonPath("$.data.sections[*].content").isNotEmpty());
    }

    @Test
    @DisplayName("没有 MSDS 的试剂：领用安全摘要 available=false，并说明原因")
    void requisitionSafetyOverviewReportsUnavailable() throws Exception {
        String reqNo = requisitionRepository.findAll().stream()
                .filter(r -> r.getChemicalId().equals(noMsdsId))
                .findFirst().orElseThrow().getReqNo();

        mockMvc.perform(get("/api/requisitions/" + reqNo + "/safety")
                        .header("Authorization", login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.sections").isEmpty())
                .andExpect(jsonPath("$.data.message").value(
                        "该试剂尚未索引 MSDS 原文，暂无法提供安全摘要"));
    }

    @Test
    @DisplayName("安全摘要跟随申请详情的权限：别人看不了，未登录 401")
    void requisitionSafetyOverviewFollowsDetailPermission() throws Exception {
        mockMvc.perform(get("/api/requisitions/" + methanolReqNo + "/safety")
                        .header("Authorization", login("bob")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/requisitions/" + methanolReqNo + "/safety"))
                .andExpect(status().isUnauthorized());
    }
}
