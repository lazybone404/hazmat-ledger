package com.lazybone.hazmat.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验禁忌关系种子文件本身，而不是通过启动流程间接验证。
 *
 * 这类数据最容易出的错是 CAS 号打错一位：规则照样入库，但永远匹配不到任何试剂，
 * 而且不会报错。把「每个 CAS 都能在试剂种子里找到」做成断言，
 * 才能在改数据时立刻发现，而不是等某次混放检查漏报。
 */
class IncompatibilitySeedDataTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("每条规则都引用已知试剂，且同一对试剂只出现一次")
    void everyRuleReferencesSeededChemicalAndPairIsUnique() throws Exception {
        JsonNode rules = read("seed/incompatibilities.json");
        Set<String> seededCas = new HashSet<>();
        for (JsonNode chemical : read("seed/chemicals.json")) {
            seededCas.add(chemical.path("casNo").asText());
        }

        assertThat(rules).hasSize(51);
        Set<String> pairs = new HashSet<>();
        for (JsonNode rule : rules) {
            String casA = rule.path("casA").asText();
            String casB = rule.path("casB").asText();
            assertThat(seededCas).as("规则引用的 CAS 必须在试剂种子里: %s", casA).contains(casA);
            assertThat(seededCas).as("规则引用的 CAS 必须在试剂种子里: %s", casB).contains(casB);
            assertThat(casA).as("不能与自己构成禁忌").isNotEqualTo(casB);
            assertThat(rule.path("reason").asText()).isNotBlank();
            assertThat(rule.path("source").asText())
                    .as("每条规则都要能追溯到出处").contains("MSDS");

            String key = casA.compareTo(casB) <= 0 ? casA + '|' + casB : casB + '|' + casA;
            assertThat(pairs.add(key)).as("重复的禁忌对: %s", key).isTrue();
        }
    }

    private JsonNode read(String path) throws Exception {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return objectMapper.readTree(in);
        }
    }
}
