package com.lazybone.hazmat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazybone.hazmat.dto.ChemicalRequest;
import com.lazybone.hazmat.dto.InboundRequest;
import com.lazybone.hazmat.entity.AppUser;
import com.lazybone.hazmat.entity.ChemicalIncompatibility;
import com.lazybone.hazmat.entity.IncompatibilitySourceType;
import com.lazybone.hazmat.entity.UserRole;
import com.lazybone.hazmat.entity.VerificationStatus;
import com.lazybone.hazmat.repository.AppUserRepository;
import com.lazybone.hazmat.repository.ChemicalBatchRepository;
import com.lazybone.hazmat.repository.ChemicalIncompatibilityRepository;
import com.lazybone.hazmat.repository.ChemicalRepository;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 禁忌混放检查的接口行为。
 *
 * 用固定的四种在库试剂、两条柜位和三条规则，而不是依赖 18 条种子数据，
 * 这样「同柜几对、分柜几对、未覆盖几对」可以精确断言。重点验证四件事：
 *   1. 只比对有库存的试剂；
 *   2. 只有共用一个柜位才叫混放，规则命中但分柜存放单独归类；
 *   3. 规则命中时带出原因、出处与共同柜位；
 *   4. 规则表里没有的组合必须显式进 uncovered，不能悄悄当成安全。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:incompatibility-it;DB_CLOSE_DELAY=-1",
        "hazmat.seed.enabled=false",
        "hazmat.security.seed-users=false",
        "hazmat.ai.encode-on-startup=false"
})
@AutoConfigureMockMvc
class IncompatibilityTest {

    private static final String PASSWORD = "TestPass123!";
    private static final String CABINET_A = "危化品柜 A-1";
    private static final String CABINET_B = "酸碱柜 B-1";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AppUserRepository userRepository;
    @Autowired private ChemicalRepository chemicalRepository;
    @Autowired private ChemicalBatchRepository batchRepository;
    @Autowired private ChemicalIncompatibilityRepository ruleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ChemicalService chemicalService;
    @Autowired private StockService stockService;

    private Long permanganateId;
    private Long noStockSulfateId;

    @BeforeEach
    void setUp() {
        ruleRepository.deleteAll();
        batchRepository.deleteAll();
        chemicalRepository.deleteAll();
        userRepository.deleteAll();

        userRepository.save(AppUser.builder()
                .username("alice").displayName("张三").role(UserRole.USER)
                .passwordHash(passwordEncoder.encode(PASSWORD)).enabled(true).build());

        // A 柜放高锰酸钾、盐酸、过氧化氢；B 柜只放硫酸，用来验证「规则命中但分柜」
        permanganateId = createAndStock("7722-64-7", "高锰酸钾", "易制爆", "高", "PK-1", CABINET_A);
        createAndStock("7647-01-0", "盐酸", "腐蚀", "中", "HC-1", CABINET_A);
        createAndStock("7664-93-9", "硫酸", "腐蚀", "中", "SU-1", CABINET_B);
        createAndStock("7722-84-1", "过氧化氢 30%", "易制爆", "高", "HP-1", CABINET_A);
        // 有档案、无批次：不应该出现在全实验室报告里
        noStockSulfateId = chemicalService.create(new ChemicalRequest(
                "7757-82-6", "无水硫酸钠", "一般", "低", "g", "AR 500g", null, null)).getId();

        // 三条规则分别覆盖依据的三态：已核实 / 有依据待核实 / 完全没有结构化依据
        ruleRepository.saveAll(List.of(
                ChemicalIncompatibility.of("7722-64-7", "7647-01-0",
                        "高锰酸钾禁配浓盐酸，反应放出氯气", "MSDS 7722-64-7 第10节",
                        IncompatibilitySourceType.MSDS,
                        "MSDS 7722-64-7",
                        "第10节 稳定性和反应性·禁配物",
                        "禁配物：浓盐酸、有机物。",
                        VerificationStatus.VERIFIED),
                ChemicalIncompatibility.of("7722-64-7", "7664-93-9",
                        "高锰酸钾与浓硫酸反应生成易爆的七氧化二锰", "MSDS 7722-64-7 第10节",
                        IncompatibilitySourceType.MSDS,
                        "MSDS 7722-64-7",
                        null,
                        null,
                        VerificationStatus.PENDING),
                ChemicalIncompatibility.of("7722-64-7", "7722-84-1",
                        "高锰酸钾禁配过氧化氢，两者混合剧烈反应", "MSDS 7722-64-7 第10节")));
    }

    @Test
    @DisplayName("全实验室报告：同柜冲突、分柜冲突、未覆盖三者的计数互不混淆")
    void reportSeparatesCoStoredFromSeparated() throws Exception {
        JsonNode data = getData("/api/incompatibilities");

        assertThat(data.path("chemicalCount").asInt()).isEqualTo(4);
        assertThat(data.path("locationCount").asInt()).isEqualTo(2);
        assertThat(data.path("pairCount").asInt()).isEqualTo(6);
        // 同柜三对：高锰酸钾-盐酸、高锰酸钾-过氧化氢、盐酸-过氧化氢
        assertThat(data.path("coStoredPairCount").asInt()).isEqualTo(3);

        assertThat(data.path("conflicts")).hasSize(2);
        assertThat(pairKeys(data.path("conflicts")))
                .containsExactlyInAnyOrder(
                        key("7647-01-0", "7722-64-7"),
                        key("7722-64-7", "7722-84-1"));
        for (JsonNode conflict : data.path("conflicts")) {
            assertThat(texts(conflict.path("sharedLocations")))
                    .containsExactly(CABINET_A);
        }

        // 高锰酸钾 + 硫酸命中规则，但一个在 A 柜、一个在 B 柜：不是同柜违规
        assertThat(data.path("separatedConflicts")).hasSize(1);
        assertThat(pairKeys(data.path("separatedConflicts")))
                .containsExactly(key("7664-93-9", "7722-64-7"));
        assertThat(texts(findPair(data.path("separatedConflicts"), "7722-64-7", "7664-93-9")
                .path("sharedLocations"))).isEmpty();

        assertThat(data.path("uncoveredCount").asInt()).isEqualTo(3);
        // 盐酸 + 过氧化氢同柜但没有规则，是最需要优先补资料的一类
        assertThat(data.path("coStoredUncoveredCount").asInt()).isEqualTo(1);
        assertThat(texts(findPair(data.path("uncovered"), "7647-01-0", "7722-84-1")
                .path("sharedLocations"))).containsExactly(CABINET_A);
        // 硫酸 + 过氧化氢是典型的「规则表没收录」组合：必须显式未覆盖
        assertThat(texts(findPair(data.path("uncovered"), "7664-93-9", "7722-84-1")
                .path("sharedLocations"))).isEmpty();

        // 依据覆盖只统计命中的三条规则：1 条已核实、1 条待核实、1 条没有结构化依据；
        // 未覆盖组合是「规则表没有」，不计入这个比例
        JsonNode coverage = data.path("basisCoverage");
        assertThat(coverage.path("total").asInt()).isEqualTo(3);
        assertThat(coverage.path("verified").asInt()).isEqualTo(1);
        assertThat(coverage.path("pending").asInt()).isEqualTo(1);
        assertThat(coverage.path("missing").asInt()).isEqualTo(1);
        assertThat(coverage.path("verifiedRatio").asDouble()).isEqualTo(0.33);

        assertThat(data.path("message").asText())
                .contains("2 对同柜禁忌混放")
                .contains("1 对已知禁忌物当前分柜存放")
                .contains("不代表可以混放");
    }

    @Test
    @DisplayName("单个试剂：命中规则时带出原因、出处与共同柜位，查询试剂固定在 A 侧")
    void chemicalCheckReturnsReasonAndSource() throws Exception {
        JsonNode data = getData("/api/chemicals/" + permanganateId + "/incompatibilities");

        assertThat(data.path("inStock").asBoolean()).isTrue();
        assertThat(data.path("chemical").path("name").asText()).isEqualTo("高锰酸钾");
        assertThat(texts(data.path("locations"))).containsExactly(CABINET_A);
        assertThat(data.path("pairCount").asInt()).isEqualTo(3);
        assertThat(data.path("coStoredPairCount").asInt()).isEqualTo(2);
        assertThat(data.path("conflicts")).hasSize(2);
        assertThat(data.path("separatedConflicts")).hasSize(1);
        assertThat(data.path("uncoveredCount").asInt()).isZero();
        assertThat(data.path("coStoredUncoveredCount").asInt()).isZero();
        assertThat(data.path("message").asText())
                .contains("2 对同柜禁忌混放")
                .contains("1 对已知禁忌物与它分柜存放");

        for (JsonNode conflict : data.path("conflicts")) {
            assertThat(conflict.path("chemicalA").path("name").asText()).isEqualTo("高锰酸钾");
            assertThat(texts(conflict.path("sharedLocations"))).containsExactly(CABINET_A);
            assertThat(conflict.path("reason").asText()).isNotBlank();
            assertThat(conflict.path("source").asText()).contains("MSDS");
        }

        // 有结构化依据的规则把 type/doc/clause/excerpt 一起带出，前端不用再翻 MSDS
        JsonNode withBasis = findPair(data.path("conflicts"), "7722-64-7", "7647-01-0");
        assertThat(withBasis.path("basis").path("type").asText()).isEqualTo("MSDS");
        assertThat(withBasis.path("basis").path("doc").asText()).contains("7722-64-7");
        assertThat(withBasis.path("basis").path("clause").asText()).contains("第10节");
        assertThat(withBasis.path("basis").path("excerpt").asText()).contains("禁配物");
        assertThat(withBasis.path("basis").path("verificationStatus").asText())
                .isEqualTo("VERIFIED");

        // 只有规则、没有条款级依据时必须显式 null，而不是伪造一个空对象
        JsonNode withoutBasis = findPair(data.path("conflicts"), "7722-64-7", "7722-84-1");
        assertThat(withoutBasis.path("basis").isNull()).isTrue();

        JsonNode separated = data.path("separatedConflicts").get(0);
        assertThat(separated.path("chemicalB").path("casNo").asText()).isEqualTo("7664-93-9");
        assertThat(texts(separated.path("sharedLocations"))).isEmpty();
    }

    @Test
    @DisplayName("同一试剂分布在多个柜位时，按柜位交集判断是否与每个搭档同柜")
    void multipleLocationsAreIntersected() throws Exception {
        // 高锰酸钾再加一批放到 B 柜，与硫酸产生共用柜位，分柜冲突应升级为同柜冲突
        stockService.inbound(permanganateId,
                new InboundRequest("PK-2", new BigDecimal("200"), CABINET_B,
                        LocalDate.now().plusYears(1)),
                "测试", LocalDate.now());

        JsonNode data = getData("/api/chemicals/" + permanganateId + "/incompatibilities");

        assertThat(texts(data.path("locations")))
                .containsExactly(CABINET_A, CABINET_B);
        assertThat(data.path("conflicts")).hasSize(3);
        assertThat(data.path("separatedConflicts")).isEmpty();
        assertThat(data.path("pairCount").asInt()).isEqualTo(3);
        assertThat(data.path("coStoredPairCount").asInt()).isEqualTo(3);
        assertThat(texts(findPair(data.path("conflicts"), "7722-64-7", "7664-93-9")
                .path("sharedLocations"))).containsExactly(CABINET_B);
    }

    @Test
    @DisplayName("有库存但规则表没有它的任何关系：全部搭档显式标成未覆盖，并标出同柜的")
    void chemicalWithoutRulesReportsEveryPartnerAsUncovered() throws Exception {
        Long ethanolId = chemicalService.create(new ChemicalRequest(
                "64-17-5", "无水乙醇", "易燃", "中", "mL", "AR 500mL", null, null)).getId();
        stockService.inbound(ethanolId,
                new InboundRequest("ET-1", new BigDecimal("500"), CABINET_A,
                        LocalDate.now().plusYears(1)),
                "测试", LocalDate.now());

        JsonNode data = getData("/api/chemicals/" + ethanolId + "/incompatibilities");

        assertThat(data.path("inStock").asBoolean()).isTrue();
        assertThat(data.path("pairCount").asInt()).isEqualTo(4);
        // 乙醇在 A 柜，与高锰酸钾、盐酸、过氧化氢同柜，与硫酸分柜
        assertThat(data.path("coStoredPairCount").asInt()).isEqualTo(3);
        assertThat(data.path("coStoredUncoveredCount").asInt()).isEqualTo(3);
        assertThat(data.path("conflicts")).isEmpty();
        assertThat(data.path("separatedConflicts")).isEmpty();
        assertThat(data.path("uncoveredCount").asInt()).isEqualTo(4);
        assertThat(data.path("message").asText()).contains("不代表可以混放");
        assertThat(texts(findPair(data.path("uncovered"), "64-17-5", "7722-64-7")
                .path("sharedLocations"))).containsExactly(CABINET_A);
    }

    @Test
    @DisplayName("无库存的试剂不参与混放检查，返回 inStock=false 而不是空冲突列表")
    void chemicalWithoutStockIsNotChecked() throws Exception {
        JsonNode data = getData("/api/chemicals/" + noStockSulfateId + "/incompatibilities");

        assertThat(data.path("inStock").asBoolean()).isFalse();
        assertThat(data.path("locations")).isEmpty();
        assertThat(data.path("pairCount").asInt()).isZero();
        assertThat(data.path("coStoredPairCount").asInt()).isZero();
        assertThat(data.path("conflicts")).isEmpty();
        assertThat(data.path("message").asText()).contains("无库存");
    }

    @Test
    @DisplayName("混放检查要求登录，未带 token 返回 401")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/incompatibilities")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/chemicals/" + permanganateId + "/incompatibilities"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("试剂不存在返回 404，而不是把空结果当成正常响应")
    void unknownChemicalReturns404() throws Exception {
        mockMvc.perform(get("/api/chemicals/999999/incompatibilities")
                        .header("Authorization", login()))
                .andExpect(status().isNotFound());
    }

    private Long createAndStock(String casNo, String name, String category, String hazardLevel,
                                String batchNo, String location) {
        Long id = chemicalService.create(new ChemicalRequest(
                casNo, name, category, hazardLevel, "g", "AR 500g", null, null)).getId();
        stockService.inbound(id,
                new InboundRequest(batchNo, new BigDecimal("500"), location,
                        LocalDate.now().plusYears(1)),
                "测试", LocalDate.now());
        return id;
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

    private JsonNode getData(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path).header("Authorization", login()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
    }

    private static JsonNode findPair(JsonNode pairs, String casA, String casB) {
        for (JsonNode pair : pairs) {
            String a = pair.path("chemicalA").path("casNo").asText();
            String b = pair.path("chemicalB").path("casNo").asText();
            if (key(a, b).equals(key(casA, casB))) {
                return pair;
            }
        }
        throw new AssertionError("响应里找不到组合 " + casA + " / " + casB);
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static Set<String> pairKeys(JsonNode pairs) {
        Set<String> keys = new HashSet<>();
        for (JsonNode pair : pairs) {
            keys.add(key(pair.path("chemicalA").path("casNo").asText(),
                    pair.path("chemicalB").path("casNo").asText()));
        }
        return keys;
    }

    private static String key(String casA, String casB) {
        return casA.compareTo(casB) <= 0 ? casA + '|' + casB : casB + '|' + casA;
    }
}
