package com.lazybone.hazmat.service;

import com.lazybone.hazmat.dto.ChemicalIncompatibilityView;
import com.lazybone.hazmat.dto.ChemicalRef;
import com.lazybone.hazmat.dto.IncompatibilityReport;
import com.lazybone.hazmat.dto.IncompatibilityView;
import com.lazybone.hazmat.dto.UncoveredPair;
import com.lazybone.hazmat.entity.Chemical;
import com.lazybone.hazmat.entity.ChemicalBatch;
import com.lazybone.hazmat.entity.ChemicalIncompatibility;
import com.lazybone.hazmat.repository.ChemicalBatchRepository;
import com.lazybone.hazmat.repository.ChemicalIncompatibilityRepository;
import com.lazybone.hazmat.repository.ChemicalRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 禁忌混放检查：把规则表与「当前实际有库存的试剂」做两两比对。
 *
 * <b>「有库存」不等于「混放」。</b>混放是物理存放问题，所以比对分两步：
 * 先用规则表判断两种试剂是否禁配，再用批次 {@code location} 求交集判断它们
 * 是否真的在同一个柜子里。规则命中但分柜存放的组合单独归入 separatedConflicts，
 * 只提示「继续保持分离」，不报成同柜违规。
 *
 * <b>为什么按库存筛：</b>档案里建过、但已经用完的试剂不占柜子，把它算进冲突
 * 只会制造噪音；反过来，已过期但瓶里还有东西的批次仍然占着柜子，所以用
 * {@code availableQuantity > 0} 而不是「未过期」。同理，只有仍有库存的批次
 * 的柜位才参与位置判断。
 *
 * <b>为什么规则一次全读进内存：</b>规则是参考数据，几百条以内；逐对查库会变成
 * O(n²) 次查询。全读建哈希表后，每对查一次内存，同时能在同一份快照里
 * 把「同柜冲突」「分柜冲突」「未覆盖」一次算清。
 *
 * <b>未覆盖不是安全。</b>本服务只回答「规则表怎么说」，不回答「能不能混放」。
 * 规则表没有的组合必须原样列进 uncovered，让使用者去补资料或人工确认；
 * 其中同柜的未覆盖组合最危险，单独计数。
 */
@Service
public class IncompatibilityService {

    private final ChemicalIncompatibilityRepository ruleRepository;
    private final ChemicalRepository chemicalRepository;
    private final ChemicalBatchRepository batchRepository;
    private final ChemicalService chemicalService;
    private final MeterRegistry meterRegistry;

    public IncompatibilityService(ChemicalIncompatibilityRepository ruleRepository,
                                  ChemicalRepository chemicalRepository,
                                  ChemicalBatchRepository batchRepository,
                                  ChemicalService chemicalService,
                                  MeterRegistry meterRegistry) {
        this.ruleRepository = ruleRepository;
        this.chemicalRepository = chemicalRepository;
        this.batchRepository = batchRepository;
        this.chemicalService = chemicalService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 禁配命中计数：{@code co_stored=true} 是同柜违规，{@code false} 是分柜存放。
     *
     * 两者分开计数而不是只数「冲突」：同柜违规的量级是安全事件，
     * 分柜命中的量级是「规则生效但没出事」，合成一个数就看不出前者有没有变多。
     */
    private void countRuleHits(int coStored, int separated) {
        if (coStored > 0) {
            meterRegistry.counter("incompatibility.conflicts", "co_stored", "true").increment(coStored);
        }
        if (separated > 0) {
            meterRegistry.counter("incompatibility.conflicts", "co_stored", "false").increment(separated);
        }
    }

    /** 全实验室混放检查：只比对当前还有实物库存的试剂 */
    @Transactional(readOnly = true)
    public IncompatibilityReport checkAll() {
        StockSnapshot snapshot = loadStock();
        List<Chemical> stock = snapshot.chemicals();
        Map<String, ChemicalIncompatibility> rules = rulesByPair();

        List<IncompatibilityView> conflicts = new ArrayList<>();
        List<IncompatibilityView> separated = new ArrayList<>();
        List<UncoveredPair> uncovered = new ArrayList<>();
        int coStoredPairs = 0;
        int coStoredUncovered = 0;

        for (int i = 0; i < stock.size(); i++) {
            for (int j = i + 1; j < stock.size(); j++) {
                Chemical a = stock.get(i);
                Chemical b = stock.get(j);
                List<String> shared = sharedLocations(
                        snapshot.locationsOf(a.getId()), snapshot.locationsOf(b.getId()));
                boolean coStored = !shared.isEmpty();
                if (coStored) {
                    coStoredPairs++;
                }
                ChemicalIncompatibility rule = rules.get(pairKey(a.getCasNo(), b.getCasNo()));
                if (rule == null) {
                    uncovered.add(UncoveredPair.of(a, b, shared));
                    if (coStored) {
                        coStoredUncovered++;
                    }
                } else if (coStored) {
                    conflicts.add(IncompatibilityView.of(a, b, shared, rule));
                } else {
                    separated.add(IncompatibilityView.of(a, b, shared, rule));
                }
            }
        }

        countRuleHits(conflicts.size(), separated.size());
        int pairCount = stock.size() * (stock.size() - 1) / 2;
        return IncompatibilityReport.of(
                stock.size(), snapshot.distinctLocationCount(), pairCount, coStoredPairs,
                conflicts, separated, uncovered, coStoredUncovered);
    }

    /** 单个试剂与其它在库试剂的比对；该试剂本身无库存时不做虚假检查 */
    @Transactional(readOnly = true)
    public ChemicalIncompatibilityView checkChemical(Long chemicalId) {
        Chemical chemical = chemicalService.findById(chemicalId);
        StockSnapshot snapshot = loadStock();
        if (!snapshot.inStock(chemicalId)) {
            return ChemicalIncompatibilityView.notInStock(ChemicalRef.from(chemical));
        }

        List<String> mine = snapshot.locationsOf(chemicalId);
        Map<String, ChemicalIncompatibility> rules = rulesByPair();
        List<IncompatibilityView> conflicts = new ArrayList<>();
        List<IncompatibilityView> separated = new ArrayList<>();
        List<UncoveredPair> uncovered = new ArrayList<>();
        int pairCount = 0;
        int coStoredPairs = 0;
        int coStoredUncovered = 0;

        for (Chemical partner : snapshot.chemicals()) {
            if (Objects.equals(partner.getId(), chemicalId)) {
                continue;
            }
            pairCount++;
            List<String> shared = sharedLocations(mine, snapshot.locationsOf(partner.getId()));
            boolean coStored = !shared.isEmpty();
            if (coStored) {
                coStoredPairs++;
            }
            ChemicalIncompatibility rule = rules.get(pairKey(chemical.getCasNo(), partner.getCasNo()));
            if (rule == null) {
                uncovered.add(UncoveredPair.of(chemical, partner, shared));
                if (coStored) {
                    coStoredUncovered++;
                }
            } else if (coStored) {
                // 查询的试剂固定放在 A 侧，前端展示时不用判断自己在哪一边
                conflicts.add(IncompatibilityView.of(chemical, partner, shared, rule));
            } else {
                separated.add(IncompatibilityView.of(chemical, partner, shared, rule));
            }
        }
        countRuleHits(conflicts.size(), separated.size());
        return ChemicalIncompatibilityView.of(
                ChemicalRef.from(chemical), mine, pairCount, coStoredPairs,
                conflicts, separated, uncovered, coStoredUncovered);
    }

    /**
     * 当前库存快照：有实物库存的试剂，以及每种试剂占用的柜位集合。
     *
     * 批次表没有指向 chemical 的外键：档案删除只发生在测试或人工清理里，
     * 正常业务不允许删档案。这里仍过滤掉找不到档案的批次，
     * 避免脏数据把 null 带进两两比对。
     */
    private StockSnapshot loadStock() {
        Map<Long, Set<String>> locationsById = new TreeMap<>();
        for (ChemicalBatch batch : batchRepository.findAllWithStock()) {
            locationsById
                    .computeIfAbsent(batch.getChemicalId(), ignored -> new TreeSet<>())
                    .add(batch.getLocation());
        }
        if (locationsById.isEmpty()) {
            return new StockSnapshot(List.of(), Map.of());
        }

        Map<Long, Chemical> found = chemicalRepository.findAllById(locationsById.keySet()).stream()
                .collect(Collectors.toMap(Chemical::getId, Function.identity()));
        List<Chemical> chemicals = new ArrayList<>(locationsById.size());
        Map<Long, Set<String>> locations = new HashMap<>();
        for (Map.Entry<Long, Set<String>> entry : locationsById.entrySet()) {
            Chemical chemical = found.get(entry.getKey());
            if (chemical != null) {
                chemicals.add(chemical);
                locations.put(chemical.getId(), entry.getValue());
            }
        }
        return new StockSnapshot(List.copyOf(chemicals), Map.copyOf(locations));
    }

    /** 两个柜位集合的交集；入参来自 TreeSet，结果按柜位名排序，响应可复现 */
    private static List<String> sharedLocations(Collection<String> a, Collection<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return List.of();
        }
        List<String> shared = new ArrayList<>();
        for (String location : a) {
            if (b.contains(location)) {
                shared.add(location);
            }
        }
        return List.copyOf(shared);
    }

    /** 规则按规范化后的 CAS 对建索引；表上唯一约束保证不会静默丢规则 */
    private Map<String, ChemicalIncompatibility> rulesByPair() {
        Map<String, ChemicalIncompatibility> rules = new HashMap<>();
        for (ChemicalIncompatibility rule : ruleRepository.findAll()) {
            rules.put(pairKey(rule.getCasA(), rule.getCasB()), rule);
        }
        return rules;
    }

    /** 与 {@link ChemicalIncompatibility#of(String, String, String, String)} 同一套排序 */
    private static String pairKey(String casA, String casB) {
        String first = ChemicalIncompatibility.orderedFirst(casA, casB);
        String second = first.equals(casA) ? casB : casA;
        return first + '|' + second;
    }

    private record StockSnapshot(List<Chemical> chemicals, Map<Long, Set<String>> locations) {

        boolean inStock(Long chemicalId) {
            return locations.containsKey(chemicalId);
        }

        List<String> locationsOf(Long chemicalId) {
            return List.copyOf(locations.getOrDefault(chemicalId, Set.of()));
        }

        int distinctLocationCount() {
            return (int) locations.values().stream()
                    .flatMap(Set::stream)
                    .distinct()
                    .count();
        }
    }
}
