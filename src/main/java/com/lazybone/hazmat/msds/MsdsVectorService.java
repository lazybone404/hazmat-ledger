package com.lazybone.hazmat.msds;

import com.lazybone.hazmat.dto.MsdsIndexStatus;
import com.lazybone.hazmat.entity.MsdsChunk;
import com.lazybone.hazmat.entity.MsdsIndexMeta;
import com.lazybone.hazmat.msds.ai.AiProperties;
import com.lazybone.hazmat.msds.ai.AiUnavailableException;
import com.lazybone.hazmat.msds.ai.EmbeddingClient;
import com.lazybone.hazmat.msds.ai.HashingEmbeddingClient;
import com.lazybone.hazmat.msds.ai.HttpEmbeddingClient;
import com.lazybone.hazmat.msds.ai.VectorCodec;
import com.lazybone.hazmat.repository.MsdsChunkRepository;
import com.lazybone.hazmat.repository.MsdsIndexMetaRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 块的向量编码：后台单线程、按批调用、失败有上限。
 *
 * 为什么编码不放在 {@link MsdsIndexService#rebuild()} 里：一次 embedding 往返几百毫秒到数秒，
 * 放进重建事务会让启动或 reindex 接口挂住，事务也会一直占着数据库连接等外部 HTTP。
 * 块先落库、向量后台补，检索在向量没就绪时仍有字面路可用。
 *
 * 四元组（模型 / 维度 / 切块版本 / 表结构版本）存在 {@code msds_index_meta}，任一项不符
 * 就整体停用而不是混用旧向量。显式 reindex 是唯一能改四元组的入口——它可能有模型调用
 * 成本，必须由用户发起。
 */
@Service
public class MsdsVectorService {

    private static final Logger log = LoggerFactory.getLogger(MsdsVectorService.class);

    /** 表结构版本。msds_chunk / msds_index_meta 的字段含义变化时升这个数 */
    public static final int SCHEMA_VERSION = 1;

    private static final int META_ID = 1;

    /** 队列上限。reindex 没有鉴权，无界队列会让重复请求把堆堆满 */
    private static final int QUEUE_CAPACITY = 8;

    private final MsdsChunkRepository chunkRepository;
    private final MsdsIndexMetaRepository metaRepository;
    private final AiProperties properties;
    private final HashingEmbeddingClient localClient;
    private final HttpEmbeddingClient remoteClient;

    /**
     * 单线程足够：几百块，串行反而好排查；也避免并发写同一行块。
     * 队列必须有界——触发编码的接口没有鉴权，无界队列在脚本循环调用下就是一个内存 DoS 入口。
     * 丢弃是安全的：排在队列里的任务仍会按最新库状态编码，不会漏块。
     */
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "msds-vector-encoder");
                thread.setDaemon(true);
                return thread;
            },
            (runnable, pool) -> log.warn("向量编码任务队列已满（{}），本次触发被丢弃", QUEUE_CAPACITY));

    private final AtomicBoolean encoding = new AtomicBoolean(false);

    public MsdsVectorService(MsdsChunkRepository chunkRepository,
                             MsdsIndexMetaRepository metaRepository,
                             AiProperties properties,
                             HashingEmbeddingClient localClient,
                             HttpEmbeddingClient remoteClient) {
        this.chunkRepository = chunkRepository;
        this.metaRepository = metaRepository;
        this.properties = properties;
        this.localClient = localClient;
        this.remoteClient = remoteClient;
    }

    /** 本轮编码的结果，写日志与状态接口都用它 */
    public record EncodeResult(int attempted, int encoded, int failed, boolean paused, String model) {
    }

    /** 显式重建时的准备结果：旧向量是否被整体作废、清了多少块 */
    public record ResetResult(boolean vectorsInvalidated, int clearedVectors, String model, String note) {
    }

    private record Quad(String modelId, int dimension, int chunkVersion, int schemaVersion) {
    }

    /** 检索时用的向量快照。编码、重建后整体替换；读路径不加锁 */
    private volatile MsdsVectorIndex vectorIndex = MsdsVectorIndex.empty();

    /** 库里有向量、但元数据与当前配置对不上；检索要据此提示重建 */
    private volatile boolean needsRebuild = false;

    /** 有 key 且开关打开才走云端，否则本地哈希兜底 */
    public EmbeddingClient activeClient() {
        return properties.hasApiKey() ? remoteClient : localClient;
    }

    public MsdsVectorIndex vectorIndex() {
        return vectorIndex;
    }

    public boolean needsRebuild() {
        return needsRebuild;
    }

    /**
     * 从库重建内存向量快照。编码、重建、启动之后调用；读侧只读 volatile 引用。
     */
    public void refreshVectorIndex() {
        try {
            List<MsdsChunk> chunks = chunkRepository.findAll();
            EmbeddingClient client = activeClient();
            Quad quad = currentQuad(client);
            MsdsIndexMeta meta = loadMeta();
            boolean metadataReady = meta != null && quadMatches(meta, quad);
            // 与编码侧同一条判据：有向量但身份不符时整路停用，不拿旧向量算余弦
            needsRebuild = hasVectors(chunks) && !metadataReady;
            vectorIndex = needsRebuild
                    ? MsdsVectorIndex.empty()
                    : MsdsVectorIndex.build(chunks, client);
        } catch (RuntimeException e) {
            // 快照是旁路：刷新失败时保留上一份，检索最多用稍旧的向量
            log.warn("刷新向量索引失败，继续使用上一份快照: {}", e.getMessage());
        }
    }

    /** 一次向量检索的结果；{@code degraded} 只描述向量路本身 */
    public record VectorSearch(List<MsdsVectorIndex.Hit> hits,
                               boolean degraded,
                               String degradedReason) {
    }

    /**
     * 把查询编码后做余弦检索。
     *
     * 失败只降级这一路，不抛给调用方：字面路仍然能返回结果，
     * 用户看到 {@code degradedReason} 就知道语义那一路没跑。
     */
    public VectorSearch search(String query, String casNo) {
        MsdsVectorIndex index = vectorIndex;
        if (index.isEmpty()) {
            return new VectorSearch(List.of(), true, "向量索引尚未就绪，仅返回字面结果");
        }
        EmbeddingClient client = activeClient();
        float[] queryVector;
        try {
            List<float[]> embedded = client.embed(List.of(query));
            if (embedded.size() != 1 || embedded.get(0) == null) {
                throw new AiUnavailableException("查询向量返回条数不符");
            }
            queryVector = embedded.get(0);
        } catch (RuntimeException e) {
            log.warn("查询向量编码失败，向量路降级: {}", e.getMessage());
            return new VectorSearch(List.of(), true, "语义检索暂不可用，仅返回字面结果");
        }
        List<MsdsVectorIndex.Hit> hits = index.search(queryVector, casNo, client.minScore());
        boolean localOnly = HashingEmbeddingClient.NAME.equals(client.name());
        return new VectorSearch(hits, localOnly,
                localOnly ? "未配置模型，使用本地哈希向量" : null);
    }

    /**
     * 编码待处理块。{@code maxChunks <= 0} 表示不限条数（生产路径）。
     * 返回后调用方可直接看 {@link #status()} 的进度。
     */
    public EncodeResult encodePending(int maxChunks) {
        EmbeddingClient client = activeClient();
        Quad quad = currentQuad(client);
        if (!encoding.compareAndSet(false, true)) {
            return new EncodeResult(0, 0, 0, false, quad.modelId());
        }
        try {
            List<MsdsChunk> chunks = chunkRepository.findAllByOrderByCasNoAscSectionNoAscPartNoAsc();
            MsdsIndexMeta meta = ensureMetaForEncoding(chunks, quad);
            if (meta == null) {
                log.warn("向量编码已停用：{}", rebuildReason(loadMeta(), quad));
                return new EncodeResult(0, 0, 0, false, quad.modelId());
            }
            if (meta.getPausedAt() != null) {
                // 暂停是持久化的：重启后不自动再打一轮模型
                return new EncodeResult(0, 0, 0, true, quad.modelId());
            }

            List<MsdsChunk> pending = chunks.stream()
                    .filter(chunk -> needsEncoding(chunk, quad))
                    .filter(chunk -> chunk.getEncodeFailures() < properties.getMaxFailuresPerChunk())
                    .limit(maxChunks > 0 ? maxChunks : Long.MAX_VALUE)
                    .toList();
            if (pending.isEmpty()) {
                return new EncodeResult(0, 0, 0, false, quad.modelId());
            }

            int batchSize = Math.max(1, properties.getBatchSize());
            int encoded = 0;
            int failed = 0;
            int consecutiveFailures = 0;

            for (int start = 0; start < pending.size(); start += batchSize) {
                List<MsdsChunk> batch = pending.subList(start, Math.min(start + batchSize, pending.size()));
                try {
                    List<float[]> vectors = client.embed(batch.stream().map(MsdsChunk::getContent).toList());
                    if (vectors.size() != batch.size()) {
                        throw new AiUnavailableException(
                                "返回条数不符: 期望 " + batch.size() + " 实际 " + vectors.size());
                    }
                    LocalDateTime now = LocalDateTime.now();
                    for (int i = 0; i < batch.size(); i++) {
                        MsdsChunk chunk = batch.get(i);
                        chunk.setVector(VectorCodec.encode(quad.modelId(), vectors.get(i)));
                        chunk.setIndexedAt(now);
                        chunk.setEncodeFailures(0);
                        chunk.setLastError(null);
                    }
                    chunkRepository.saveAll(batch);
                    encoded += batch.size();
                    consecutiveFailures = 0;
                } catch (AiUnavailableException e) {
                    failed += batch.size();
                    consecutiveFailures += batch.size();
                    recordFailure(batch, e.getMessage());
                    log.warn("MSDS 块编码失败（本批 {} 块）: {}", batch.size(), e.getMessage());
                    if (consecutiveFailures >= properties.getMaxConsecutiveFailures()) {
                        pause(meta, "连续 " + consecutiveFailures + " 块编码失败：" + e.getMessage());
                        log.warn("向量编码暂停：连续 {} 块失败，原因 {}", consecutiveFailures, e.getMessage());
                        return new EncodeResult(pending.size(), encoded, failed, true, quad.modelId());
                    }
                }
            }
            EncodeResult result = new EncodeResult(pending.size(), encoded, failed, false, quad.modelId());
            log.info("MSDS 向量编码完成: {}", result);
            return result;
        } finally {
            encoding.set(false);
            // 提前 return（暂停、元数据不符）也要刷新：部分批次可能已经写成功
            refreshVectorIndex();
        }
    }

    /** 后台编码。单线程 executor 保证多次触发串行，不会并发写同一批块 */
    public void scheduleEncoding() {
        executor.submit(() -> {
            try {
                encodePending(0);
            } catch (RuntimeException e) {
                // 编码是旁路，失败不能影响台账主流程
                log.error("MSDS 向量编码任务异常", e);
            }
        });
    }

    /** 启动时自动编码，可在配置里关闭（测试与离线环境不需要它跑） */
    public void scheduleEncodingOnStartup() {
        if (properties.isEncodeOnStartup()) {
            scheduleEncoding();
        }
    }

    /**
     * 显式重建前的准备：四元组变了就作废全部旧向量；没变就保留，只清失败与暂停。
     *
     * 这个区分是有意的——切块规则或模型没变时重编码全部块是纯浪费，
     * 而模型换了还留着旧向量，余弦会算出一堆看似正常、实际毫无意义的分数。
     */
    public ResetResult prepareForReindex() {
        EmbeddingClient client = activeClient();
        Quad quad = currentQuad(client);
        List<MsdsChunk> chunks = chunkRepository.findAllByOrderByCasNoAscSectionNoAscPartNoAsc();
        MsdsIndexMeta meta = loadMeta();
        boolean quadMatches = meta != null && quadMatches(meta, quad);
        boolean hasVectors = hasVectors(chunks);

        int cleared = 0;
        // 无论走哪条路径，显式重建都清失败计数：用户点这一下就是要重试。
        // 只写真正变了的行：288 块里通常只有少数带失败记录，全表 saveAll 是白写
        List<MsdsChunk> dirty = new ArrayList<>();
        for (MsdsChunk chunk : chunks) {
            boolean changed = false;
            if (!quadMatches && chunk.getVector() != null && !chunk.getVector().isBlank()) {
                chunk.setVector(null);
                chunk.setIndexedAt(null);
                cleared++;
                changed = true;
            }
            if (chunk.getEncodeFailures() != 0 || chunk.getLastError() != null) {
                chunk.setEncodeFailures(0);
                chunk.setLastError(null);
                changed = true;
            }
            if (changed) {
                dirty.add(chunk);
            }
        }
        if (!dirty.isEmpty()) {
            chunkRepository.saveAll(dirty);
        }

        MsdsIndexMeta current = meta == null ? new MsdsIndexMeta() : meta;
        current.setId(META_ID);
        applyQuad(current, quad);
        current.setPausedAt(null);
        current.setPausedReason(null);
        current.setUpdatedAt(LocalDateTime.now());
        metaRepository.save(current);

        boolean invalidated = !quadMatches && cleared > 0;
        String note = quadMatches
                ? "四元组未变，保留已有向量，只重试缺失与失败的块"
                : "四元组已变化，旧向量作废，按当前配置重新编码";
        // 旧向量刚被清掉，快照必须同步失效，否则下一次检索还会拿它们算余弦
        refreshVectorIndex();
        log.info("MSDS 索引重建准备完成: {}（作废 {} 块，模型 {}）", note, cleared, quad.modelId());
        return new ResetResult(invalidated, cleared, quad.modelId(), note);
    }

    /** 状态接口的数据源。每次全量扫一遍：几百行，比维护计数器的失效逻辑便宜 */
    public MsdsIndexStatus status() {
        List<MsdsChunk> chunks = chunkRepository.findAll();
        EmbeddingClient client = activeClient();
        Quad quad = currentQuad(client);
        MsdsIndexMeta meta = loadMeta();
        boolean hasVectors = hasVectors(chunks);
        boolean metadataReady = meta != null && quadMatches(meta, quad);
        boolean needsRebuild = hasVectors && !metadataReady;

        int encoded = 0;
        int failed = 0;
        int pending = 0;
        for (MsdsChunk chunk : chunks) {
            if (hasUsableVector(chunk, quad)) {
                encoded++;
            } else if (chunk.getEncodeFailures() >= properties.getMaxFailuresPerChunk()) {
                failed++;
            } else {
                pending++;
            }
        }

        return new MsdsIndexStatus(
                chunks.size(), encoded, pending, failed,
                meta != null && meta.getPausedAt() != null,
                meta == null ? null : meta.getPausedReason(),
                meta == null ? null : meta.getPausedAt(),
                quad.modelId(), quad.dimension(), quad.chunkVersion(), quad.schemaVersion(),
                metadataReady, needsRebuild,
                needsRebuild ? rebuildReason(meta, quad) : null,
                encoding.get());
    }

    public boolean isEncoding() {
        return encoding.get();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private MsdsIndexMeta ensureMetaForEncoding(List<MsdsChunk> chunks, Quad quad) {
        MsdsIndexMeta meta = loadMeta();
        if (meta != null && quadMatches(meta, quad)) {
            return meta;
        }
        // 库里没有任何向量时，改元数据没有成本，也不存在「混用旧向量」的风险，
        // 可以直接按当前配置写；有向量才必须等用户显式重建
        if (hasVectors(chunks)) {
            return null;
        }
        MsdsIndexMeta created = meta == null ? new MsdsIndexMeta() : meta;
        created.setId(META_ID);
        applyQuad(created, quad);
        created.setPausedAt(null);
        created.setPausedReason(null);
        created.setUpdatedAt(LocalDateTime.now());
        metaRepository.save(created);
        log.info("初始化向量索引元数据: {}", quad);
        return created;
    }

    private void recordFailure(List<MsdsChunk> batch, String message) {
        String reason = truncate(message);
        for (MsdsChunk chunk : batch) {
            chunk.setEncodeFailures(chunk.getEncodeFailures() + 1);
            chunk.setLastError(reason);
        }
        chunkRepository.saveAll(batch);
    }

    private void pause(MsdsIndexMeta meta, String reason) {
        meta.setPausedAt(LocalDateTime.now());
        meta.setPausedReason(truncate(reason));
        metaRepository.save(meta);
    }

    private MsdsIndexMeta loadMeta() {
        return metaRepository.findById(META_ID).orElse(null);
    }

    private Quad currentQuad(EmbeddingClient client) {
        return new Quad(client.name(), client.dimensions(),
                MsdsChunker.CHUNK_VERSION, SCHEMA_VERSION);
    }

    private static boolean quadMatches(MsdsIndexMeta meta, Quad quad) {
        return Objects.equals(meta.getModelId(), quad.modelId())
                && Objects.equals(meta.getDimension(), quad.dimension())
                && Objects.equals(meta.getChunkVersion(), quad.chunkVersion())
                && Objects.equals(meta.getSchemaVersion(), quad.schemaVersion());
    }

    private static void applyQuad(MsdsIndexMeta meta, Quad quad) {
        meta.setModelId(quad.modelId());
        meta.setDimension(quad.dimension());
        meta.setChunkVersion(quad.chunkVersion());
        meta.setSchemaVersion(quad.schemaVersion());
    }

    private static boolean hasVectors(List<MsdsChunk> chunks) {
        return chunks.stream().anyMatch(chunk -> chunk.getVector() != null && !chunk.getVector().isBlank());
    }

    private static boolean needsEncoding(MsdsChunk chunk, Quad quad) {
        return !hasUsableVector(chunk, quad);
    }

    /** 向量能解出来、模型与维度都和当前配置一致，才算可用 */
    private static boolean hasUsableVector(MsdsChunk chunk, Quad quad) {
        VectorCodec.Decoded decoded = VectorCodec.decode(chunk.getVector());
        return decoded != null
                && decoded.model().equals(quad.modelId())
                && decoded.vector().length == quad.dimension();
    }

    private String rebuildReason(MsdsIndexMeta meta, Quad quad) {
        if (meta == null) {
            return "库里已有向量，但索引元数据缺失，无法确认向量来自哪个模型";
        }
        List<String> diffs = new ArrayList<>();
        if (!Objects.equals(meta.getModelId(), quad.modelId())) {
            diffs.add("模型 " + meta.getModelId() + " → " + quad.modelId());
        }
        if (!Objects.equals(meta.getDimension(), quad.dimension())) {
            diffs.add("维度 " + meta.getDimension() + " → " + quad.dimension());
        }
        if (!Objects.equals(meta.getChunkVersion(), quad.chunkVersion())) {
            diffs.add("切块版本 " + meta.getChunkVersion() + " → " + quad.chunkVersion());
        }
        if (!Objects.equals(meta.getSchemaVersion(), quad.schemaVersion())) {
            diffs.add("表结构版本 " + meta.getSchemaVersion() + " → " + quad.schemaVersion());
        }
        return "索引元数据与当前配置不一致（" + String.join("；", diffs) + "），旧向量已停用，需手动重建";
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 255 ? text : text.substring(0, 255);
    }
}
