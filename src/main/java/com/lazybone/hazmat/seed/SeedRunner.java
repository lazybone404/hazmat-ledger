package com.lazybone.hazmat.seed;

import com.lazybone.hazmat.dto.ChemicalRequest;
import com.lazybone.hazmat.dto.InboundRequest;
import com.lazybone.hazmat.entity.Chemical;
import com.lazybone.hazmat.repository.ChemicalRepository;
import com.lazybone.hazmat.service.ChemicalService;
import com.lazybone.hazmat.service.StockService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 启动时灌一批试剂档案与批次，让空库也有东西可查。
 *
 * <b>它复用的是真实的入库路径（{@link StockService#inbound}），不是直接往表里插。</b>
 * 这一点是有意选的，理由是：种子数据如果绕过业务逻辑，就永远发现不了
 * 「入库路径本身写错了」——而种子数据是我们每次启动都会跑的第一段代码，
 * 它其实是**最廉价的一次冒烟测试**。代价是慢一点、依赖更重，值得。
 *
 * <b>种子文件里的日期全部是相对天数，没有一个绝对日期。</b>
 * 这一条是被真实事故逼出来的：原来写的是绝对日期（如 {@code "expireDate": "2026-09-15"}），
 * 而演示需要「已过期」和「临期」两类批次，于是种子里必须放一个已过期的批次。
 * 但入库校验不允许已过期批次进来——两条从写下来那天起就是矛盾的，
 * 只因当时那个日期还在未来所以没炸。2026-10-10 日期一到，
 * 种子灌到第三个批次时抛 BusinessException，**整个应用启动失败**。
 *
 * 光把日期换成相对天数还不够，还得走
 * {@link StockService#inbound(Long, InboundRequest, String, java.time.LocalDate)}
 * 那个带入库日期的重载：一批「入库时半年后到期、现在已过期」的货，
 * 校验必须相对**入库那一天**算，否则相对日期一样过不去。
 */
@Slf4j
@Component
@Order(10)
@ConditionalOnProperty(name = "hazmat.seed.enabled", havingValue = "true")
public class SeedRunner implements ApplicationRunner {

    private static final String SEED_FILE = "seed/chemicals.json";
    private static final String SEED_OPERATOR = "初始化";

    private final ChemicalRepository chemicalRepository;
    private final ChemicalService chemicalService;
    private final StockService stockService;
    private final ObjectMapper objectMapper;

    public SeedRunner(ChemicalRepository chemicalRepository,
                      ChemicalService chemicalService,
                      StockService stockService,
                      ObjectMapper objectMapper) {
        this.chemicalRepository = chemicalRepository;
        this.chemicalService = chemicalService;
        this.stockService = stockService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (chemicalRepository.count() > 0) {
            log.info("库里已有 {} 条试剂档案，跳过种子数据", chemicalRepository.count());
            return;
        }
        ClassPathResource resource = new ClassPathResource(SEED_FILE);
        if (!resource.exists()) {
            log.warn("找不到种子文件 {}，跳过", SEED_FILE);
            return;
        }

        LocalDate today = LocalDate.now();
        int chemicals = 0;
        int batches = 0;
        int backfilled = 0;
        try (InputStream in = resource.getInputStream()) {
            List<SeedChemical> seeds = objectMapper.readValue(in, new TypeReference<List<SeedChemical>>() {
            });
            for (SeedChemical s : seeds) {
                Chemical created = chemicalService.create(new ChemicalRequest(
                        s.casNo(), s.name(), s.category(), s.hazardLevel(), s.unit(), s.spec(),
                        s.msdsUrl(), s.postOpenDays()));
                chemicals++;
                for (SeedBatch b : s.batches()) {
                    stockService.inbound(created.getId(),
                            new InboundRequest(b.batchNo(), b.quantity(), b.location(), b.expireDate(today)),
                            SEED_OPERATOR,
                            b.inboundDate(today));
                    batches++;
                    if (b.inboundDaysAgo() != null && b.inboundDaysAgo() > 0) {
                        backfilled++;
                    }
                }
            }
        }
        log.info("已灌入 {} 条试剂档案、{} 个批次（其中 {} 个是历史回填，来自 {}，基准日期 {}）",
                chemicals, batches, backfilled, SEED_FILE, today);
    }

    /**
     * 种子文件的结构。用嵌套 record 而不是复用 DTO，是因为它是**文件格式**不是接口契约：
     * 文件里批次是嵌在试剂里的（一次采购一批，写在一起才好维护），而接口是分开调的。
     * 硬把两者合成一个类，会逼着接口也接受嵌套结构。
     *
     * 两个日期都是相对天数，一律相对「启动那一天」算——这样种子数据永远不会腐烂。
     *
     * {@code postOpenDays} 可缺省：JSON 里没写就是 null，该试剂不追踪开封后可用期。
     */
    record SeedChemical(String casNo, String name, String category, String hazardLevel,
                        String unit, String spec, String msdsUrl, Integer postOpenDays,
                        List<SeedBatch> batches) {
    }

    record SeedBatch(String batchNo, BigDecimal quantity, String location,
                     Integer inboundDaysAgo, Integer expireInDays) {

        /** 入库日期 = 今天往回推 inboundDaysAgo 天 */
        LocalDate inboundDate(LocalDate today) {
            if (inboundDaysAgo == null) {
                throw new IllegalStateException("种子批次 " + batchNo + " 缺 inboundDaysAgo");
            }
            return today.minusDays(inboundDaysAgo);
        }

        /** 有效期 = 今天往后推 expireInDays 天；负数表示已经过期 */
        LocalDate expireDate(LocalDate today) {
            if (expireInDays == null) {
                throw new IllegalStateException("种子批次 " + batchNo + " 缺 expireInDays");
            }
            return today.plusDays(expireInDays);
        }
    }
}
