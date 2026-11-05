package com.lazybone.hazmat.seed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazybone.hazmat.entity.ChemicalIncompatibility;
import com.lazybone.hazmat.repository.ChemicalIncompatibilityRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 启动时灌入禁忌混放规则。
 *
 * <b>与 {@link SeedRunner} 分开的原因：</b>试剂档案只在空库时灌，而规则表是参考数据，
 * 可能在上一次启动后新增、也可能在已有档案的库里独立补齐。两件事的生命周期不同，
 * 塞进同一个「库里有档案就整体跳过」的分支里，会让升级后的库永远没有规则。
 *
 * <b>种子内容只来自仓库内的 MSDS 第 10 节</b>，{@code source} 字段写明是哪份 MSDS。
 * 没有百分之百的规则覆盖——表里没有的组合必须由接口标为「未覆盖」，
 * 不能因为种子跑完了就认为全部试剂都检查过。
 *
 * 与 {@link SeedRunner} 一样受 {@code hazmat.seed.enabled} 控制；
 * 生产环境导入完整的 GB 15603 关系表时，可以关掉它避免演示规则混入。
 */
@Slf4j
@Component
@Order(20)
@ConditionalOnProperty(name = "hazmat.seed.enabled", havingValue = "true")
public class IncompatibilitySeedRunner implements ApplicationRunner {

    private static final String SEED_FILE = "seed/incompatibilities.json";

    private final ChemicalIncompatibilityRepository ruleRepository;
    private final ObjectMapper objectMapper;

    public IncompatibilitySeedRunner(ChemicalIncompatibilityRepository ruleRepository,
                                     ObjectMapper objectMapper) {
        this.ruleRepository = ruleRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        ClassPathResource resource = new ClassPathResource(SEED_FILE);
        if (!resource.exists()) {
            log.warn("找不到禁忌关系种子文件 {}，跳过", SEED_FILE);
            return;
        }

        // 规范化后去重：文件里允许按任意方向写一对 CAS，但同一对只能落一条
        Map<String, ChemicalIncompatibility> seeds = new LinkedHashMap<>();
        try (InputStream in = resource.getInputStream()) {
            List<SeedRule> rules = objectMapper.readValue(in, new TypeReference<List<SeedRule>>() {
            });
            for (SeedRule seed : rules) {
                ChemicalIncompatibility rule = ChemicalIncompatibility.of(
                        seed.casA(), seed.casB(), seed.reason(), seed.source());
                String key = key(rule);
                if (seeds.putIfAbsent(key, rule) != null) {
                    log.warn("种子文件里存在重复的禁忌对 {}，后一条被忽略", key);
                }
            }
        }

        Set<String> existing = ruleRepository.findAll().stream()
                .map(this::key)
                .collect(Collectors.toSet());
        List<ChemicalIncompatibility> missing = seeds.entrySet().stream()
                .filter(entry -> !existing.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();

        if (missing.isEmpty()) {
            log.info("禁忌关系表已有 {} 条规则，无需灌入", existing.size());
            return;
        }
        ruleRepository.saveAll(missing);
        log.info("已灌入 {} 条禁忌关系（文件共 {} 条，已有 {} 条，来自 {}）",
                missing.size(), seeds.size(), existing.size(), SEED_FILE);
    }

    private String key(ChemicalIncompatibility rule) {
        return rule.getCasA() + '|' + rule.getCasB();
    }

    /** 种子文件格式。字段名直接用 casA/casB，与数据库列和一方向约定一致 */
    record SeedRule(String casA, String casB, String reason, String source) {
    }
}
