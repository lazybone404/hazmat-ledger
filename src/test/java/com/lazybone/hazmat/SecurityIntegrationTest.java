package com.lazybone.hazmat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazybone.hazmat.dto.ChemicalRequest;
import com.lazybone.hazmat.dto.InboundRequest;
import com.lazybone.hazmat.dto.RequisitionRequest;
import com.lazybone.hazmat.entity.AppUser;
import com.lazybone.hazmat.entity.Requisition;
import com.lazybone.hazmat.entity.UserRole;
import com.lazybone.hazmat.repository.AppUserRepository;
import com.lazybone.hazmat.repository.ChemicalBatchRepository;
import com.lazybone.hazmat.repository.ChemicalRepository;
import com.lazybone.hazmat.repository.RequisitionLogRepository;
import com.lazybone.hazmat.repository.RequisitionRepository;
import com.lazybone.hazmat.repository.ScrapOrderRepository;
import com.lazybone.hazmat.repository.StockLedgerRepository;
import com.lazybone.hazmat.repository.StocktakeItemRepository;
import com.lazybone.hazmat.repository.StocktakeRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证与授权的端到端测试：走真实的 Security 过滤器链与 {@code @PreAuthorize}。
 *
 * 这一层必须用 MockMvc 而不是直接调 Service——直接调 Service 不经过认证上下文，
 * 「谁在调用」这个最关键的前提根本不存在，测不到 401/403，也测不到「申请人来自 token」。
 *
 * 用独立 H2 库名，避免与其它 {@code @SpringBootTest} 上下文的 create-drop 互相清表。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:security-it;DB_CLOSE_DELAY=-1",
        "hazmat.security.seed-users=false",
        "hazmat.ai.encode-on-startup=false"
})
@AutoConfigureMockMvc
class SecurityIntegrationTest {

    private static final String PASSWORD = "TestPass123!";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AppUserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ChemicalService chemicalService;
    @Autowired private RequisitionService requisitionService;
    @Autowired private StockService stockService;
    @Autowired private ChemicalRepository chemicalRepository;
    @Autowired private ChemicalBatchRepository batchRepository;
    @Autowired private RequisitionRepository requisitionRepository;
    @Autowired private RequisitionLogRepository requisitionLogRepository;
    @Autowired private ScrapOrderRepository scrapOrderRepository;
    @Autowired private StockLedgerRepository ledgerRepository;
    @Autowired private StocktakeRepository stocktakeRepository;
    @Autowired private StocktakeItemRepository stocktakeItemRepository;

    private Long chemicalId;

    @BeforeEach
    void setUp() {
        stocktakeItemRepository.deleteAll();
        stocktakeRepository.deleteAll();
        scrapOrderRepository.deleteAll();
        requisitionLogRepository.deleteAll();
        requisitionRepository.deleteAll();
        ledgerRepository.deleteAll();
        batchRepository.deleteAll();
        chemicalRepository.deleteAll();
        userRepository.deleteAll();

        createUser("admin", "系统管理员", UserRole.ADMIN);
        createUser("approver1", "审批员甲", UserRole.APPROVER);
        createUser("alice", "张三", UserRole.USER);
        createUser("bob", "李四", UserRole.USER);

        chemicalId = chemicalService.create(new ChemicalRequest(
                "CAS-SEC-001", "安全测试试剂", "易燃", "中", "mL", "AR 500mL", null, null)).getId();
    }

    @Test
    @DisplayName("自助注册默认关闭：匿名调用返回 403，而不是 401")
    void registrationIsForbiddenWhenSwitchOff() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "newcomer",
                                "displayName", "新同事",
                                "password", PASSWORD))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        assertThat(userRepository.findByUsername("newcomer")).isEmpty();
    }

    private void createUser(String username, String displayName, UserRole role) {
        userRepository.save(AppUser.builder()
                .username(username)
                .displayName(displayName)
                .role(role)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .enabled(true)
                .build());
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
        String token = body.path("data").path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private Requisition submitAsAlice() {
        return requisitionService.submit("alice", new RequisitionRequest(
                chemicalId, new BigDecimal("5"), "安全测试"));
    }

    @Test
    @DisplayName("没有 token：401，且响应体仍是统一的 ApiResponse")
    void withoutTokenReturns401Json() throws Exception {
        mockMvc.perform(get("/api/chemicals"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("登录成功拿到 token，/me 能取回当前用户")
    void loginAndMeReturnsCurrentUser() throws Exception {
        String token = login("alice");

        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("密码错误与用户不存在返回同一句话，不泄露账号是否存在")
    void wrongPasswordAndUnknownUserLookTheSame() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "alice", "password", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "nobody", "password", "whatever"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    @DisplayName("普通用户不能建档：403；管理员可以")
    void onlyAdminCanCreateChemical() throws Exception {
        String content = objectMapper.writeValueAsString(Map.of(
                "casNo", "CAS-SEC-002", "name", "禁止普通用户建档",
                "category", "易燃", "hazardLevel", "中",
                "unit", "mL", "spec", "AR 500mL"));

        mockMvc.perform(post("/api/chemicals")
                        .header("Authorization", bearer(login("alice")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(content))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(post("/api/chemicals")
                        .header("Authorization", bearer(login("admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(content))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.casNo").value("CAS-SEC-002"));
    }

    @Test
    @DisplayName("MSDS 索引状态限管理员：普通用户 403，管理员 200")
    void msdsStatusRequiresAdmin() throws Exception {
        mockMvc.perform(get("/api/msds/status")
                        .header("Authorization", bearer(login("alice"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(get("/api/msds/status")
                        .header("Authorization", bearer(login("admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").exists());
    }

    @Test
    @DisplayName("审批限 APPROVER / ADMIN：普通用户 403，审批员可以通过")
    void onlyApproverCanReview() throws Exception {
        Requisition r = submitAsAlice();
        String body = objectMapper.writeValueAsString(Map.of("approved", true, "comment", "同意"));

        mockMvc.perform(post("/api/requisitions/" + r.getReqNo() + "/review")
                        .header("Authorization", bearer(login("alice")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/requisitions/" + r.getReqNo() + "/review")
                        .header("Authorization", bearer(login("approver1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    @Test
    @DisplayName("申请人来自 token，而不是请求体：请求里没有 applicant 也照样记在 alice 名下")
    void applicantComesFromToken() throws Exception {
        mockMvc.perform(post("/api/requisitions")
                        .header("Authorization", bearer(login("alice")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "chemicalId", chemicalId,
                                "quantity", 3,
                                "purpose", "身份绑定测试"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applicant").value("alice"));
    }

    @Test
    @DisplayName("普通用户只能查自己的申请；不传 applicant 默认查自己")
    void userCanOnlyListOwnRequisitions() throws Exception {
        submitAsAlice();
        String aliceToken = login("alice");

        mockMvc.perform(get("/api/requisitions").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1));

        mockMvc.perform(get("/api/requisitions").param("applicant", "alice")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/requisitions").param("applicant", "bob")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("申请详情：本人与审批员能看，其他普通用户看不到")
    void detailIsRestrictedToOwnerOrApprover() throws Exception {
        Requisition r = submitAsAlice();

        mockMvc.perform(get("/api/requisitions/" + r.getReqNo())
                        .header("Authorization", bearer(login("bob"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/requisitions/" + r.getReqNo())
                        .header("Authorization", bearer(login("alice"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/requisitions/" + r.getReqNo())
                        .header("Authorization", bearer(login("approver1"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("探活端点不需要登录，其它 actuator 端点没有暴露")
    void healthIsPublicButOtherActuatorEndpointsAreNot() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        // 未暴露的端点即使带着 token 也是 404，而不是把 env / heapdump 这类信息放出去
        mockMvc.perform(get("/actuator/env")
                        .header("Authorization", bearer(login("admin"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("接口文档可匿名访问，供 Swagger UI 加载")
    void openApiDocumentIsPublic() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/auth/login']").exists());

        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("路径参数校验失败返回 400，而不是 500 或 404")
    void invalidPathVariableReturns400() throws Exception {
        mockMvc.perform(get("/api/chemicals/0/stock")
                        .header("Authorization", bearer(login("alice"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("请求体不是合法 JSON：400，而不是 500")
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/chemicals")
                        .header("Authorization", bearer(login("admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"casNo\":\"CAS-BAD\",\"name\":\"坏编码"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("撤回用当前登录身份：bob 撤不了 alice 的，alice 可以")
    void cancelUsesAuthenticatedUser() throws Exception {
        Requisition r = submitAsAlice();

        mockMvc.perform(post("/api/requisitions/" + r.getReqNo() + "/cancel")
                        .header("Authorization", bearer(login("bob"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/api/requisitions/" + r.getReqNo() + "/cancel")
                        .header("Authorization", bearer(login("alice"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("盘点写接口限管理员：普通用户 403，管理员建单成功且创建人来自 token")
    void stocktakeWriteRequiresAdmin() throws Exception {
        stockService.inbound(chemicalId,
                new InboundRequest("SEC-ST-1", new BigDecimal("20"), "柜 B-1",
                        LocalDate.now().plusYears(1)), "admin");
        String body = objectMapper.writeValueAsString(Map.of("remark", "权限测试"));

        mockMvc.perform(post("/api/stocktakes")
                        .header("Authorization", bearer(login("alice")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(post("/api/stocktakes")
                        .header("Authorization", bearer(login("admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.createdBy").value("admin"))
                .andExpect(jsonPath("$.data.items.length()").value(1));
    }

    @Test
    @DisplayName("报废单：普通用户可提交、不能审批；审批员可批；他人不能撤销")
    void scrapOrderFollowsRoleMatrix() throws Exception {
        Long batchId = stockService.inbound(chemicalId,
                new InboundRequest("SEC-SCRAP-1", new BigDecimal("50"), "柜 C-1",
                        LocalDate.now().plusYears(1)), "admin").id();
        String aliceToken = bearer(login("alice"));
        String approverToken = bearer(login("approver1"));

        MvcResult created = mockMvc.perform(post("/api/scraps")
                        .header("Authorization", aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "chemicalId", chemicalId,
                                "batchId", batchId,
                                "quantity", 10,
                                "reason", "容器破损"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applicant").value("alice"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn();
        String scrapNo = objectMapper.readTree(
                        created.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("scrapNo").asText();

        mockMvc.perform(post("/api/scraps/" + scrapNo + "/review")
                        .header("Authorization", aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("approved", true, "comment", "自己批"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/scraps/" + scrapNo + "/cancel")
                        .header("Authorization", bearer(login("bob"))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/scraps/" + scrapNo + "/review")
                        .header("Authorization", approverToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("approved", true, "comment", "同意"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        // 普通用户查别人的报废单是 403；审批员可以
        mockMvc.perform(get("/api/scraps?applicant=alice")
                        .header("Authorization", bearer(login("bob"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/scraps?applicant=alice")
                        .header("Authorization", approverToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1));
    }

    @Test
    @DisplayName("盘点与对账的读接口对所有登录用户开放：普通用户也能查")
    void stocktakeReadIsOpenToAllLoggedInUsers() throws Exception {
        stockService.inbound(chemicalId,
                new InboundRequest("SEC-ST-2", new BigDecimal("20"), "柜 B-2",
                        LocalDate.now().plusYears(1)), "admin");
        String alice = bearer(login("alice"));

        mockMvc.perform(get("/api/stocktakes").header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());

        mockMvc.perform(get("/api/chemicals/" + chemicalId + "/reconcile")
                        .header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consistent").value(true));
    }

    @Test
    @DisplayName("演示前端静态资源匿名可打开，业务接口仍然 401")
    void staticFrontendIsPublicButApiIsNot() throws Exception {
        // 首页显式 no-cache：演示时浏览器不能拿旧页面配新接口
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-cache")))
                .andExpect(content().string(containsString("危化品台账")));

        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));

        mockMvc.perform(get("/app.js"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/styles.css"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/vendor/vue.global.prod.js"))
                .andExpect(status().isOk());

        // 放行的只是页面，业务接口不跟着裸奔
        mockMvc.perform(get("/api/chemicals"))
                .andExpect(status().isUnauthorized());
    }
}
