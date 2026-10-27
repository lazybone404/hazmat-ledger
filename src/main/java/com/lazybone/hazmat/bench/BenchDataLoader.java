package com.lazybone.hazmat.bench;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 压测数据装载器。<b>默认关闭</b>，只有显式传入 {@code --hazmat.bench.enabled=true} 才执行。
 *
 * 为什么用 JdbcTemplate 直接插而不是走 Service：目的是造量，不是验证业务。
 * 走 {@code StockService.inbound} 一条一条插，二十万条流水要跑很久；
 * 而且压测需要的是「分布可控的数据」，不是「业务正确的写入路径」。
 * 真实路径的冒烟已经由 {@code SeedRunner} 承担，两者职责不同。
 *
 * <b>但有一条必须手工保证：流水与批次余额自洽。</b>每批造 11 笔流水，
 * 第一笔入库 100，之后 5 组「+20 / -20」相互抵消，最终余额 100 = 批次可用量，
 * 最后一笔的 {@code balance_after} 也落在 100。否则压测 {@code /reconcile} 时
 * 每次都会返回 {@code consistent=false}，测出来的耗时不代表正常路径。
 *
 * <b>默认参数接近真实规模，调大只是探测器。</b>默认的 2000 试剂 × 10 批次与 5000 份申请
 * 大致对应校园级台账五年的量级；把 chemicals 调到 1 万、requisitions 调到 2 万，
 * 是为了把「随扫描行数线性退化」的接口（关键词搜索、报表全表聚合）放大出来，
 * 让退化趋势盖过测量波动。<b>放大的数据不代表预期业务量，不要在文档里当规模引用。</b>
 *
 * <b>usable_until 必须显式写。</b>它允许 NULL，而 {@code usable_until >= today}
 * 对 NULL 恒为 unknown——漏写这一列，所有批次会从「可出库」查询里消失，
 * 压测的可用量统计会全变成 0，而且不报错。实体上的 {@code @PrePersist} 兜底
 * 只对 JPA 写入生效，JdbcTemplate 不经过它。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hazmat.bench.enabled", havingValue = "true")
public class BenchDataLoader implements ApplicationRunner {

    /** 每批 11 笔：1 笔入库 + 5 组抵消，净额与批次余额一致 */
    private static final int LEDGER_PER_BATCH = 11;
    private static final BigDecimal BATCH_QTY = new BigDecimal("100");
    private static final BigDecimal STEP = new BigDecimal("20");

    @Value("${hazmat.bench.chemicals:2000}")
    private int chemicalCount;

    @Value("${hazmat.bench.batches-per-chemical:10}")
    private int batchesPerChemical;

    @Value("${hazmat.bench.requisitions:5000}")
    private int requisitionCount;

    private final JdbcTemplate jdbc;

    public BenchDataLoader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer existing = jdbc.queryForObject("select count(*) from chemical", Integer.class);
        if (existing != null && existing > 0) {
            log.info("[bench] 库里已有 {} 条试剂，跳过压测数据装载", existing);
            return;
        }

        long start = System.currentTimeMillis();
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        loadChemicals(now);
        List<Long> chemicalIds = jdbc.queryForList("select id from chemical order by id", Long.class);
        loadBatches(today, now, chemicalIds);
        loadLedger(now);
        loadRequisitions(now, chemicalIds);

        log.info("[bench] 压测数据装载完成：{} 试剂、{} 批次、{} 流水、{} 申请，耗时 {} ms",
                count("chemical"), count("chemical_batch"), count("stock_ledger"),
                count("requisition"), System.currentTimeMillis() - start);
    }

    private void loadChemicals(LocalDateTime now) {
        List<Object[]> rows = new ArrayList<>(chemicalCount);
        for (int i = 1; i <= chemicalCount; i++) {
            rows.add(new Object[]{
                    "BENCH-" + i,
                    "压测试剂 " + i,
                    "类别" + (i % 10),
                    i % 5 == 0 ? "高" : (i % 3 == 0 ? "中" : "低"),
                    "mL",
                    "AR 500mL",
                    null,
                    // 三分之一的试剂跟踪开封后可用期，让开封相关分支也有数据
                    i % 3 == 0 ? 30 : null,
                    now.minusDays(i)
            });
        }
        batchInsert("""
                insert into chemical
                    (cas_no, name, category, hazard_level, unit, spec, msds_url, post_open_days, created_at)
                values (?,?,?,?,?,?,?,?,?)
                """, rows);
    }

    private void loadBatches(LocalDate today, LocalDateTime now, List<Long> chemicalIds) {
        List<Object[]> rows = new ArrayList<>(chemicalIds.size() * batchesPerChemical);
        int seq = 0;
        for (Long chemicalId : chemicalIds) {
            for (int b = 0; b < batchesPerChemical; b++) {
                int offset = switch (seq % 10) {
                    // 10% 已过期、10% 临期、80% 正常，让库存视图与预警都有真实分布
                    case 0 -> -(1 + seq % 90);
                    case 1 -> 1 + seq % 25;
                    default -> 60 + seq % 800;
                };
                LocalDate expire = today.plusDays(offset);
                rows.add(new Object[]{
                        chemicalId,
                        "B-" + chemicalId + "-" + b,
                        BATCH_QTY,
                        BATCH_QTY,
                        "柜 " + (chemicalId % 50),
                        expire,
                        expire,
                        now.minusDays(seq % 365)
                });
                seq++;
            }
        }
        batchInsert("""
                insert into chemical_batch
                    (chemical_id, batch_no, initial_quantity, available_quantity, location,
                     expire_date, usable_until, created_at)
                values (?,?,?,?,?,?,?,?)
                """, rows);
    }

    private void loadLedger(LocalDateTime now) {
        List<Object[]> batches = jdbc.query(
                "select id, chemical_id from chemical_batch order by id",
                (rs, rowNum) -> new Object[]{rs.getLong(1), rs.getLong(2)});

        List<Object[]> rows = new ArrayList<>(batches.size() * LEDGER_PER_BATCH);
        for (Object[] batch : batches) {
            Long batchId = (Long) batch[0];
            Long chemicalId = (Long) batch[1];
            BigDecimal balance = BigDecimal.ZERO;
            for (int k = 0; k < LEDGER_PER_BATCH; k++) {
                boolean inbound = k == 0 || k % 2 == 1;
                BigDecimal qty = k == 0 ? BATCH_QTY : (inbound ? STEP : STEP.negate());
                balance = balance.add(qty);
                rows.add(new Object[]{
                        batchId, chemicalId, inbound ? "IN" : "OUT", qty, balance,
                        "BENCH", null, "bench", now.minusDays(30 - k)
                });
            }
        }
        batchInsert("""
                insert into stock_ledger
                    (batch_id, chemical_id, change_type, quantity, balance_after,
                     ref_type, ref_id, operator, created_at)
                values (?,?,?,?,?,?,?,?,?)
                """, rows);
    }

    private void loadRequisitions(LocalDateTime now, List<Long> chemicalIds) {
        String[] statuses = {"PENDING", "APPROVED", "ISSUED", "CLOSED", "REJECTED"};
        List<Object[]> rows = new ArrayList<>(requisitionCount);
        for (int i = 0; i < requisitionCount; i++) {
            rows.add(new Object[]{
                    "REQ-BENCH-" + i,
                    "user" + (i % 20),
                    chemicalIds.get(i % chemicalIds.size()),
                    new BigDecimal("10"),
                    "压测申请 " + i,
                    statuses[i % statuses.length],
                    0L,
                    now.minusMinutes(i)
            });
        }
        batchInsert("""
                insert into requisition
                    (req_no, applicant, chemical_id, quantity, purpose, status, version, created_at)
                values (?,?,?,?,?,?,?,?)
                """, rows);
    }

    private void batchInsert(String sql, List<Object[]> rows) {
        int chunk = 1000;
        for (int from = 0; from < rows.size(); from += chunk) {
            jdbc.batchUpdate(sql, rows.subList(from, Math.min(from + chunk, rows.size())));
        }
    }

    private int count(String table) {
        Integer value = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return value == null ? 0 : value;
    }
}
