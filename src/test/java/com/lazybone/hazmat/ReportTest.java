package com.lazybone.hazmat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazybone.hazmat.dto.ChemicalRequest;
import com.lazybone.hazmat.dto.InboundRequest;
import com.lazybone.hazmat.entity.AppUser;
import com.lazybone.hazmat.entity.UserRole;
import com.lazybone.hazmat.repository.AppUserRepository;
import com.lazybone.hazmat.repository.ChemicalBatchRepository;
import com.lazybone.hazmat.repository.ChemicalRepository;
import com.lazybone.hazmat.repository.RequisitionLogRepository;
import com.lazybone.hazmat.repository.RequisitionRepository;
import com.lazybone.hazmat.repository.StockLedgerRepository;
import com.lazybone.hazmat.service.ChemicalService;
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
 * 报表接口测试：验证 MyBatis-Plus 的 XML SQL 在真实查询链路里能跑通，
 * 且聚合口径（按分类+单位、过期与临期互斥）正确。
 *
 * 独立 H2 库名，避免与其它上下文互相清表。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:report-it;DB_CLOSE_DELAY=-1",
        "hazmat.security.seed-users=false",
        "hazmat.ai.encode-on-startup=false"
})
@AutoConfigureMockMvc
class ReportTest {

    private static final String PASSWORD = "TestPass123!";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AppUserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ChemicalService chemicalService;
    @Autowired private StockService stockService;
    @Autowired private ChemicalRepository chemicalRepository;
    @Autowired private ChemicalBatchRepository batchRepository;
    @Autowired private RequisitionRepository requisitionRepository;
    @Autowired private RequisitionLogRepository requisitionLogRepository;
    @Autowired private StockLedgerRepository ledgerRepository;

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

        LocalDate today = LocalDate.now();

        // 易燃 / mL：两个试剂，三个可用批次，其中一批过期、一批临期
        Long ethanolId = chemicalService.create(new ChemicalRequest(
                "64-17-5", "乙醇", "易燃", "中", "mL", "AR 500mL", null, null)).getId();
        Long methanolId = chemicalService.create(new ChemicalRequest(
                "67-56-1", "甲醇", "易燃", "高", "mL", "AR 500mL", null, null)).getId();
        stockService.inbound(ethanolId, new InboundRequest(
                "R-EXPIRED", new BigDecimal("100"), "A-01", today.minusDays(5)),
                "admin", today.minusDays(60));
        stockService.inbound(ethanolId, new InboundRequest(
                "R-SOON", new BigDecimal("50"), "A-02", today.plusDays(10)),
                "admin", today.minusDays(10));
        stockService.inbound(methanolId, new InboundRequest(
                "R-FRESH", new BigDecimal("200"), "A-03", today.plusDays(200)),
                "admin", today.minusDays(10));

        // 腐蚀 / g：只建档，没有任何批次。报表仍应出现这一行，数量为 0
        chemicalService.create(new ChemicalRequest(
                "7664-93-9", "硫酸", "腐蚀", "高", "g", "AR 500g", null, null));
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
    @DisplayName("库存汇总按分类+单位分组，无批次的试剂仍占一行")
    void stockSummaryGroupsByCategoryAndUnit() throws Exception {
        mockMvc.perform(get("/api/reports/stock-summary").param("withinDays", "30")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].category").value("易燃"))
                .andExpect(jsonPath("$.data[0].unit").value("mL"))
                // 两个试剂：乙醇 2 个批次 + 甲醇 1 个，COUNT(DISTINCT chemical) 保证试剂数不按批次重复计
                .andExpect(jsonPath("$.data[0].chemicalCount").value(2))
                .andExpect(jsonPath("$.data[0].batchCount").value(3))
                .andExpect(jsonPath("$.data[0].totalAvailable").value(350.0))
                .andExpect(jsonPath("$.data[0].expiringQuantity").value(50.0))
                .andExpect(jsonPath("$.data[0].expiredQuantity").value(100.0))
                .andExpect(jsonPath("$.data[1].category").value("腐蚀"))
                .andExpect(jsonPath("$.data[1].chemicalCount").value(1))
                .andExpect(jsonPath("$.data[1].batchCount").value(0))
                .andExpect(jsonPath("$.data[1].totalAvailable").value(0.0));
    }

    @Test
    @DisplayName("预警窗口变大时，临期量计入但不重复算进过期量")
    void expiringWindowWidensWithoutDoubleCounting() throws Exception {
        mockMvc.perform(get("/api/reports/stock-summary").param("withinDays", "365")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].expiringQuantity").value(250.0))
                .andExpect(jsonPath("$.data[0].expiredQuantity").value(100.0));

        // withinDays=0 只看已过期：今天到期的批次不算过期
        mockMvc.perform(get("/api/reports/stock-summary").param("withinDays", "0")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].expiringQuantity").value(0.0))
                .andExpect(jsonPath("$.data[0].expiredQuantity").value(100.0));
    }

    @Test
    @DisplayName("出入库趋势按日期与类型汇总，数量带符号")
    void flowTrendGroupsByDateAndType() throws Exception {
        mockMvc.perform(get("/api/reports/flow-trend").param("days", "7")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].changeType").value("IN"))
                .andExpect(jsonPath("$.data[0].recordCount").value(3))
                .andExpect(jsonPath("$.data[0].totalQuantity").value(350.0))
                .andExpect(jsonPath("$.data[0].statDate").value(LocalDate.now().toString()));
    }

    @Test
    @DisplayName("报表参数越界返回 400，未登录返回 401")
    void reportParametersAreValidated() throws Exception {
        mockMvc.perform(get("/api/reports/stock-summary").param("withinDays", "366")
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/reports/flow-trend").param("days", "0")
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/reports/stock-summary"))
                .andExpect(status().isUnauthorized());
    }
}
