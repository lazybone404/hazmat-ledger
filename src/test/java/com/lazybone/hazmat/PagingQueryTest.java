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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分页与组合查询的端到端测试。
 *
 * 走 MockMvc 而不是直接调 Service：参数绑定（{@code size=101}、{@code status=乱写}、
 * {@code from=2026-13-01}）与白名单拒绝都发生在 Web 层，直接调 Service 一个都测不到。
 *
 * 独立 H2 库名，避免与其它 {@code @SpringBootTest} 上下文互相清表。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:paging-it;DB_CLOSE_DELAY=-1",
        "hazmat.security.seed-users=false",
        "hazmat.ai.encode-on-startup=false"
})
@AutoConfigureMockMvc
class PagingQueryTest {

    private static final String PASSWORD = "TestPass123!";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AppUserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ChemicalService chemicalService;
    @Autowired private StockService stockService;
    @Autowired private RequisitionService requisitionService;
    @Autowired private ChemicalRepository chemicalRepository;
    @Autowired private ChemicalBatchRepository batchRepository;
    @Autowired private RequisitionRepository requisitionRepository;
    @Autowired private RequisitionLogRepository requisitionLogRepository;
    @Autowired private StockLedgerRepository ledgerRepository;

    private Long methanolId;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        requisitionLogRepository.deleteAll();
        requisitionRepository.deleteAll();
        ledgerRepository.deleteAll();
        batchRepository.deleteAll();
        chemicalRepository.deleteAll();
        userRepository.deleteAll();

        userRepository.save(AppUser.builder()
                .username("admin").displayName("系统管理员").role(UserRole.ADMIN)
                .passwordHash(passwordEncoder.encode(PASSWORD)).enabled(true).build());
        adminToken = login("admin");

        methanolId = chemicalService.create(new ChemicalRequest(
                "67-56-1", "甲醇", "易燃", "高", "mL", "AR 500mL", null, null)).getId();
        chemicalService.create(new ChemicalRequest(
                "64-17-5", "乙醇", "易燃", "中", "mL", "AR 500mL", null, null));
        chemicalService.create(new ChemicalRequest(
                "7664-93-9", "硫酸", "腐蚀", "高", "mL", "AR 500mL", null, null));
        // 名称里带 %，用来验证 LIKE 通配符被当成普通字符
        chemicalService.create(new ChemicalRequest(
                "50-00-0", "甲醛50%溶液", "有毒", "高", "mL", "AR 500mL", null, null));

        LocalDate today = LocalDate.now();
        // 已过期批次：入库日期锚在过去，否则「不许收已过期的货」会拒绝
        stockService.inbound(methanolId, new InboundRequest(
                "B-EXPIRED", new BigDecimal("10"), "A-01", today.minusDays(10)),
                "admin", today.minusDays(60));
        stockService.inbound(methanolId, new InboundRequest(
                "B-SOON", new BigDecimal("20"), "A-02", today.plusDays(5)),
                "admin", today.minusDays(10));
        stockService.inbound(methanolId, new InboundRequest(
                "B-FRESH", new BigDecimal("30"), "A-03", today.plusDays(200)),
                "admin", today.minusDays(10));
    }

    private String login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private String bearer() {
        return "Bearer " + adminToken;
    }

    @Test
    @DisplayName("试剂列表返回统一分页结构，而不是裸数组")
    void chemicalListIsPaged() throws Exception {
        mockMvc.perform(get("/api/chemicals").param("page", "0").param("size", "2")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(4))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(true));
    }

    @Test
    @DisplayName("页大小无效或超上限返回 400，不是 500")
    void invalidPageParametersReturn400() throws Exception {
        mockMvc.perform(get("/api/chemicals").param("size", "101").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(get("/api/chemicals").param("size", "0").header("Authorization", bearer()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/chemicals").param("page", "-1").header("Authorization", bearer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("排序字段必须在白名单内，方向只能是 asc/desc")
    void sortWhitelistIsEnforced() throws Exception {
        mockMvc.perform(get("/api/chemicals").param("sort", "msdsUrl")
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("可用字段")));

        mockMvc.perform(get("/api/chemicals").param("sort", "name,sideways")
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest());

        // 不写死 id：@BeforeEach 的 deleteAll 不会重置 H2 的自增序列，
        // 断言绝对 id 会在第二个测试方法开始就失败
        long maxId = chemicalRepository.findAllByOrderByIdAsc().getLast().getId();
        mockMvc.perform(get("/api/chemicals").param("sort", "id,desc")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value((int) maxId));
    }

    @Test
    @DisplayName("关键词匹配名称与 CAS，% 按普通字符处理")
    void keywordMatchesNameAndCasWithEscapedWildcard() throws Exception {
        mockMvc.perform(get("/api/chemicals").param("keyword", "50%")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].name").value("甲醛50%溶液"));

        mockMvc.perform(get("/api/chemicals").param("keyword", "7664")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].casNo").value("7664-93-9"));
    }

    @Test
    @DisplayName("分类与危险等级可以组合筛选")
    void categoryAndHazardLevelCombine() throws Exception {
        mockMvc.perform(get("/api/chemicals").param("category", "易燃")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));

        mockMvc.perform(get("/api/chemicals").param("category", "易燃").param("hazardLevel", "高")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].name").value("甲醇"));
    }

    @Test
    @DisplayName("申请列表分页，并按状态与日期范围筛选")
    void requisitionListIsFiltered() throws Exception {
        requisitionService.submit("admin", new RequisitionRequest(
                methanolId, new BigDecimal("2"), "分页测试-1"));
        requisitionService.submit("admin", new RequisitionRequest(
                methanolId, new BigDecimal("3"), "分页测试-2"));

        mockMvc.perform(get("/api/requisitions").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content.length()").value(2));

        mockMvc.perform(get("/api/requisitions").param("status", "PENDING")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));

        mockMvc.perform(get("/api/requisitions").param("status", "APPROVED")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));

        LocalDate today = LocalDate.now();
        mockMvc.perform(get("/api/requisitions")
                        .param("from", today.toString())
                        .param("to", today.toString())
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));

        mockMvc.perform(get("/api/requisitions")
                        .param("from", today.plusDays(1).toString())
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @DisplayName("非法状态、非法日期、开始晚于结束都返回 400")
    void invalidRequisitionFiltersReturn400() throws Exception {
        mockMvc.perform(get("/api/requisitions").param("status", "NOT_A_STATUS")
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(get("/api/requisitions").param("from", "2026-13-01")
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/requisitions")
                        .param("from", LocalDate.now().toString())
                        .param("to", LocalDate.now().minusDays(1).toString())
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("开始日期不能晚于结束日期"));
    }

    @Test
    @DisplayName("流水按时间倒序分页，试剂不存在返回 404")
    void ledgerIsPagedAndMissingChemicalReturns404() throws Exception {
        mockMvc.perform(get("/api/chemicals/" + methanolId + "/ledger")
                        .param("sort", "id,desc")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content[0].changeType").value("IN"))
                // balanceAfter 是「该批次发生后的余额」，不是该试剂的总量：
                // 三次入库分别落在三个批次上，最新一批入库 30
                .andExpect(jsonPath("$.data.content[0].balanceAfter").value(30.0));

        mockMvc.perform(get("/api/chemicals/999999/ledger").header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("效期预警含已过期批次，按到期时间升序")
    void expiringIncludesExpiredAndSortsByDate() throws Exception {
        mockMvc.perform(get("/api/chemicals/expiring").param("withinDays", "30")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].batchNo").value("B-EXPIRED"))
                .andExpect(jsonPath("$.data.content[0].daysToUsable").value(-10))
                .andExpect(jsonPath("$.data.content[0].chemicalName").value("甲醇"))
                .andExpect(jsonPath("$.data.content[1].batchNo").value("B-SOON"));

        mockMvc.perform(get("/api/chemicals/expiring").param("withinDays", "365")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3));

        mockMvc.perform(get("/api/chemicals/expiring").param("withinDays", "0")
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("同一排序值的记录靠 id 兜底，翻页不重不漏")
    void stableSortAppendsId() throws Exception {
        JsonNode firstPage = fetchChemicalPage(0, 2);
        JsonNode secondPage = fetchChemicalPage(1, 2);

        long firstPageTotal = firstPage.path("totalElements").asLong();
        assertThat(firstPageTotal).isEqualTo(4);
        assertThat(secondPage.path("totalElements").asLong()).isEqualTo(4);

        java.util.Set<Long> ids = new java.util.HashSet<>();
        firstPage.path("content").forEach(n -> ids.add(n.path("id").asLong()));
        secondPage.path("content").forEach(n -> ids.add(n.path("id").asLong()));
        assertThat(ids).hasSize(4);
    }

    private JsonNode fetchChemicalPage(int page, int size) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/chemicals")
                        .param("page", String.valueOf(page))
                        .param("size", String.valueOf(size))
                        .param("sort", "category,asc")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
    }
}
