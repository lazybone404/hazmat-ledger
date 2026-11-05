package com.lazybone.hazmat.msds;

import com.lazybone.hazmat.common.BusinessException;
import com.lazybone.hazmat.dto.MsdsHit;
import com.lazybone.hazmat.dto.MsdsSearchResult;
import com.lazybone.hazmat.entity.Chemical;
import com.lazybone.hazmat.entity.MsdsChunk;
import com.lazybone.hazmat.repository.ChemicalRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MSDS 检索入口：字面路 + 向量路，用 RRF 融合。
 *
 * 为什么不用分数相加：字面路是 IDF 加权和，向量路是余弦，两者量纲不可比。
 * 要做加权和就得先归一化，而归一化本身又引入一堆超参；RRF 只看名次，
 * 两条路的分数分布怎么变都不影响融合逻辑。
 *
 * 检索不要求先知道是哪一种试剂：用户问「误食怎么办」，应该同时看到甲醇、丙酮的急救措施，
 * 所以 casNo 是可选过滤条件，不是必填。
 */
@Service
public class MsdsSearchService {

    /** 查询串不是越长越好：分词、同义词扩展、每个块打分都与长度线性相关 */
    private static final int MAX_QUERY_LENGTH = 200;

    /** 接口最多返回 20 条；内部候选深度由 {@link MsdsRetrievalProperties} 控制 */
    private static final int MAX_TOP_K = 20;

    private static final String LITERAL = "literal";
    private static final String VECTOR = "vector";

    private final MsdsIndexService indexService;
    private final MsdsVectorService vectorService;
    private final ChemicalRepository chemicalRepository;
    private final MsdsRetrievalProperties retrieval;
    private final SearchConcurrencyLimiter limiter;

    public MsdsSearchService(MsdsIndexService indexService,
                             MsdsVectorService vectorService,
                             ChemicalRepository chemicalRepository,
                             MsdsRetrievalProperties retrieval,
                             SearchConcurrencyLimiter limiter) {
        this.indexService = indexService;
        this.vectorService = vectorService;
        this.chemicalRepository = chemicalRepository;
        this.retrieval = retrieval;
        this.limiter = limiter;
    }

    /**
     * 不标 {@code @Transactional}：查询向量可能是一次外部 HTTP 调用（云端模型），
     * 把连接握在事务里等几秒是不必要的；读档案与读块各自有短事务。
     */
    public MsdsSearchResult search(String query, String casNo, int topK) {
        if (query == null || query.isBlank()) {
            throw new BusinessException("检索内容不能为空");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new BusinessException("检索内容最长 " + MAX_QUERY_LENGTH + " 个字符");
        }
        String normalized = query.trim();

        // 参数错误（400）先于限流判断：调用方改参数就能解决的事，没必要占一个许可。
        // 闸门只保护向量路：许可拿不到时跳过向量检索，字面路照常跑。
        // 「检索可以少一路，但不能空白」——返回空列表会让用户以为库里没有这条知识，
        // 而实际上只是这一刻并发满了。
        boolean vectorPermit = limiter.tryAcquire();
        try {
            // 档案只有十几条，每次查一遍即可；为它加缓存反而多一份失效逻辑
            Map<String, String> names = chemicalRepository.findAll().stream()
                    .collect(Collectors.toMap(Chemical::getCasNo, Chemical::getName, (a, b) -> a));

            int depth = retrieval.effectiveDepth();
            MsdsLiteralIndex index = indexService.literalIndex();
            List<MsdsLiteralIndex.Hit> literal = index.search(normalized, casNo, depth);
            MsdsVectorService.VectorSearch vector = vectorPermit
                    ? vectorService.search(normalized, casNo)
                    : new MsdsVectorService.VectorSearch(
                            List.of(), true, "检索并发已满，向量路降级，仅返回字面结果");

            List<MsdsHit> hits = fuse(index, literal, vector.hits(), topK, names);
            return new MsdsSearchResult(
                    hits,
                    vector.degraded(),
                    vector.degradedReason(),
                    vectorService.isEncoding(),
                    vectorService.needsRebuild());
        } finally {
            // 只有真正拿到许可才释放；无条件 release 会把许可数越抬越高，闸门形同虚设
            if (vectorPermit) {
                limiter.release();
            }
        }
    }

    /**
     * RRF：{@code score = Σ w_i / (k + rank_i)}，rank 从 1 开始。
     *
     * 两路名次越靠前贡献越大；只有一路命中的块不会被另一路的低名次拖累。
     * 排序并列时用 CAS / 节号 / 块序号兜底，保证同一查询的结果稳定可复现。
     */
    private List<MsdsHit> fuse(MsdsLiteralIndex index,
                               List<MsdsLiteralIndex.Hit> literal,
                               List<MsdsVectorIndex.Hit> vector,
                               int topK,
                               Map<String, String> names) {
        // 权重按当前向量路分档：本地哈希 3:1，语义模型 1:2。见 MsdsRetrievalProperties
        boolean semantic = vectorService.usesSemanticEmbedding();
        double literalWeight = retrieval.literalWeightFor(semantic);
        double vectorWeight = retrieval.vectorWeightFor(semantic);

        Map<Long, Fused> fused = new LinkedHashMap<>();
        for (int rank = 0; rank < literal.size(); rank++) {
            MsdsChunk chunk = literal.get(rank).chunk();
            fused.computeIfAbsent(chunk.getId(), id -> new Fused(chunk))
                    .add(LITERAL, literalWeight / (retrieval.getRrfK() + rank + 1));
        }
        for (int rank = 0; rank < vector.size(); rank++) {
            MsdsChunk chunk = vector.get(rank).chunk();
            fused.computeIfAbsent(chunk.getId(), id -> new Fused(chunk))
                    .add(VECTOR, vectorWeight / (retrieval.getRrfK() + rank + 1));
        }

        List<Fused> ordered = new ArrayList<>(fused.values());
        ordered.sort((a, b) -> {
            int byScore = Double.compare(b.score, a.score);
            if (byScore != 0) {
                return byScore;
            }
            int byCas = a.chunk.getCasNo().compareTo(b.chunk.getCasNo());
            if (byCas != 0) {
                return byCas;
            }
            int bySection = Integer.compare(a.chunk.getSectionNo(), b.chunk.getSectionNo());
            return bySection != 0 ? bySection : Integer.compare(a.chunk.getPartNo(), b.chunk.getPartNo());
        });

        int limit = Math.max(1, Math.min(topK, MAX_TOP_K));
        return ordered.stream()
                .limit(limit)
                .map(fusedHit -> new MsdsHit(
                        fusedHit.chunk.getId(),
                        fusedHit.chunk.getCasNo(),
                        chemicalName(fusedHit.chunk.getCasNo(), names, index),
                        fusedHit.chunk.getSectionNo(),
                        fusedHit.chunk.getSectionTitle(),
                        fusedHit.chunk.getContent(),
                        fusedHit.score,
                        List.copyOf(fusedHit.sources)))
                .toList();
    }

    /**
     * 展示名优先用台账档案，其次用索引里记录的文档名称，最后才退回 CAS 号。
     *
     * 只查档案会让「没有建台账的 MSDS」在结果里显示成 CAS 号，
     * 而文档第 1 节本来就写了中文名称，没理由不用。
     */
    private static String chemicalName(String casNo, Map<String, String> registryNames,
                                       MsdsLiteralIndex index) {
        String name = registryNames.get(casNo);
        if (name == null || name.isBlank()) {
            name = index.nameOf(casNo);
        }
        return name == null || name.isBlank() ? casNo : name;
    }

    /** 融合过程中的一行：块、累计分、命中来源 */
    private static final class Fused {
        private final MsdsChunk chunk;
        private final Set<String> sources = new LinkedHashSet<>();
        private double score;

        private Fused(MsdsChunk chunk) {
            this.chunk = chunk;
        }

        private void add(String source, double contribution) {
            sources.add(source);
            score += contribution;
        }
    }
}
