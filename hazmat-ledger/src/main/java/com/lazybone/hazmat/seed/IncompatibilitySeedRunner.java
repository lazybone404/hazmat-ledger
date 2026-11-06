package com.lazybone.hazmat.seed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazybone.hazmat.entity.ChemicalIncompatibility;
import com.lazybone.hazmat.entity.IncompatibilitySourceType;
import com.lazybone.hazmat.entity.VerificationStatus;
import com.lazybone.hazmat.repository.ChemicalIncompatibilityRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                        seed.casA(), seed.casB(), seed.reason(), seed.source(),
                        sourceType(seed.sourceType()), seed.sourceDoc(), seed.sourceClause(),
                        seed.sourceExcerpt(), verificationStatus(seed.verificationStatus()));
                String key = key(rule);
                if (seeds.putIfAbsent(key, rule) != null) {
                    log.warn("种子文件里存在重复的禁忌对 {}，后一条被忽略", key);
                }
            }
        }

        Map<String, ChemicalIncompatibility> existing = new LinkedHashMap<>();
        for (ChemicalIncompatibility rule : ruleRepository.findAll()) {
            existing.put(key(rule), rule);
        }
        List<ChemicalIncompatibility> missing = new ArrayList<>();
        List<ChemicalIncompatibility> upgraded = new ArrayList<>();
        for (Map.Entry<String, ChemicalIncompatibility> entry : seeds.entrySet()) {
            ChemicalIncompatibility current = existing.get(entry.getKey());
            if (current == null) {
                missing.add(entry.getValue());
                continue;
            }
            // 旧库升级：V10 之前落库的规则只有 source 一行文本，这里补结构化依据。
            // applyBasis 只填空字段，不覆盖人工修正
            ChemicalIncompatibility seed = entry.getValue();
            current.applyBasis(seed.getSourceType(), seed.getSourceDoc(), seed.getSourceClause(),
                    seed.getSourceExcerpt(), seed.getVerificationStatus());
            if (current.getSourceDoc() != null && current.getSourceExcerpt() != null) {
                upgraded.add(current);
            }
        }

        if (!missing.isEmpty()) {
            ruleRepository.saveAll(missing);
            log.info("已灌入 {} 条禁忌关系（文件共 {} 条，已有 {} 条，来自 {}）",
                    missing.size(), seeds.size(), existing.size(), SEED_FILE);
        } else {
            log.info("禁忌关系表已有 {} 条规则，无需新增", existing.size());
        }
        if (!upgraded.isEmpty()) {
            ruleRepository.saveAll(upgraded);
            log.info("已为 {} 条旧规则补齐结构化依据", upgraded.size());
        }
    }

    private String key(ChemicalIncompatibility rule) {
        return rule.getCasA() + '|' + rule.getCasB();
    }

    private static IncompatibilitySourceType sourceType(String value) {
        return value == null || value.isBlank()
                ? IncompatibilitySourceType.MSDS
                : IncompatibilitySourceType.valueOf(value.trim());
    }

    private static VerificationStatus verificationStatus(String value) {
        return value == null || value.isBlank()
                ? VerificationStatus.PENDING
                : VerificationStatus.valueOf(value.trim());
    }

    /** 种子文件格式。字段名直接用 casA/casB，与数据库列和一方向约定一致 */
    record SeedRule(String casA, String casB, String reason, String source,
                    String sourceType, String sourceDoc, String sourceClause,
                    String sourceExcerpt, String verificationStatus) {
    }
}
