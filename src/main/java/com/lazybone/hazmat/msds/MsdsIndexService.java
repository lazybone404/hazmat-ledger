package com.lazybone.hazmat.msds;

import com.lazybone.hazmat.entity.Chemical;
import com.lazybone.hazmat.entity.KnowledgeDocType;
import com.lazybone.hazmat.entity.KnowledgeDocument;
import com.lazybone.hazmat.entity.MsdsChunk;
import com.lazybone.hazmat.msds.knowledge.ChunkerRegistry;
import com.lazybone.hazmat.msds.knowledge.DocumentChunker;
import com.lazybone.hazmat.msds.knowledge.TextChunk;
import com.lazybone.hazmat.repository.ChemicalRepository;
import com.lazybone.hazmat.repository.KnowledgeDocumentRepository;
import com.lazybone.hazmat.repository.MsdsChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 把 {@code classpath:msds/*.md} 切块落库，并在内存里维护字面索引。
 *
 * 增量粒度是**块内容**而不是块下标：在文档中间插入一节时，下标匹配会让后面每一块
 * 都错开一位、整篇重算；按 {@code casNo + textHash} 匹配则只影响真正变了的那几块。
 * 复用块保留 {@code vector} 与 {@code indexedAt}，向量是否仍然有效由
 * {@link MsdsVectorService} 按模型名和四元组判断，重建本身不重算向量。
 */
@Service
public class MsdsIndexService {

    private static final Logger log = LoggerFactory.getLogger(MsdsIndexService.class);
    private static final String MSDS_PATTERN = "classpath*:msds/*.md";
    private static final Pattern NAME_PATTERN = Pattern.compile("中文名称：\\s*([^\\s（(]+)");
    private static final Pattern TITLE_PATTERN = Pattern.compile("^#\\s+(.+?)\\s*$", Pattern.MULTILINE);
    /** 与 rebuild 里给块加的名称前缀保持一致，从库里恢复索引时靠它取回名称 */
    private static final Pattern SUBJECT_PREFIX = Pattern.compile("^\\[([^\\]]+)] ");

    private final MsdsChunkRepository chunkRepository;
    private final ChemicalRepository chemicalRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final ChunkerRegistry chunkers;
    private final TransactionTemplate transactionTemplate;
    private final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    /**
     * 重建是「先查旧块、再删新增」的读改写，两个请求同时进来会各插一份同样内容。
     * 锁必须包住整个事务，而不是只包住方法体：{@code @Transactional} 的提交发生在
     * 代理返回之后，方法体内 unlock 时另一个线程仍看不到已提交的数据。
     * 单实例内的锁；多实例部署要靠数据库唯一约束或分布式锁，当前项目是单实例。
     */
    private final ReentrantLock rebuildLock = new ReentrantLock();

    private volatile MsdsLiteralIndex literalIndex = MsdsLiteralIndex.empty();

    public MsdsIndexService(MsdsChunkRepository chunkRepository,
                            ChemicalRepository chemicalRepository,
                            KnowledgeDocumentRepository documentRepository,
                            ChunkerRegistry chunkers,
                            TransactionTemplate transactionTemplate) {
        this.chunkRepository = chunkRepository;
        this.chemicalRepository = chemicalRepository;
        this.documentRepository = documentRepository;
        this.chunkers = chunkers;
        this.transactionTemplate = transactionTemplate;
    }

    /** 重建结果，直接作为 reindex 接口的返回体 */
    public record RebuildResult(int files, int chunks, int reused, int created, int deleted) {
    }

    /**
     * 全量扫描 + 增量落库。事务包住整个重建：中途失败时库里不会留下「删了一半、写了一半」的状态。
     * 锁在事务外层，避免并发重建看到同一份旧快照、各插一份。
     *
     * <b>索引必须在事务提交之后构建：</b>{@code TransactionTemplate.execute} 正常返回时提交已完成，
     * 此时读到的块才真正存在。若在 {@code doRebuild()} 内提前替换 {@code literalIndex}，
     * 一旦提交失败或回滚，内存索引就会指向一批已回滚的、库里并不存在的块——
     * 之后所有检索都会从这些「幽灵块」里取结果，直到下一次重建。
     * 代价是重建末尾多一次全表查询（几百行量级），换来的是索引与库的强一致。
     */
    public RebuildResult rebuild() {
        rebuildLock.lock();
        try {
            RebuildResult result = transactionTemplate.execute(status -> doRebuild());
            refreshFromDatabase();
            return result;
        } finally {
            rebuildLock.unlock();
        }
    }

    private RebuildResult doRebuild() {
        List<MsdsChunk> existing = chunkRepository.findAllByOrderByCasNoAscSectionNoAscPartNoAsc();
        Map<String, Deque<MsdsChunk>> reusableByHash = new HashMap<>();
        for (MsdsChunk chunk : existing) {
            reusableByHash.computeIfAbsent(key(chunk.getCasNo(), chunk.getTextHash()), k -> new ArrayDeque<>())
                    .add(chunk);
        }

        // 档案里有名字就以档案为准：检索结果展示的和用户输入要能对上，
        // 文件里的「乙醇」和档案里的「无水乙醇」混用时，界面上会出现两个叫法
        List<Chemical> chemicals = chemicalRepository.findAll();
        Map<String, Long> chemicalIds = chemicals.stream()
                .collect(Collectors.toMap(Chemical::getCasNo, Chemical::getId, (a, b) -> a));
        Map<String, String> registryNames = chemicals.stream()
                .collect(Collectors.toMap(Chemical::getCasNo, Chemical::getName, (a, b) -> a));

        List<MsdsChunk> current = new ArrayList<>();
        int files = 0;
        int reused = 0;
        int created = 0;

        for (Resource resource : msdsResources()) {
            String filename = resource.getFilename();
            String casNo = filename.substring(0, filename.length() - ".md".length());
            String markdown;
            try {
                markdown = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("读取 MSDS 文件失败: " + filename, e);
            }

            List<MsdsChunker.Chunk> chunks = MsdsChunker.split(markdown);
            if (chunks.isEmpty()) {
                log.warn("MSDS 文件没有解析出任何章节，已跳过: {}", filename);
                continue;
            }
            files++;

            String chemicalName = registryNames.getOrDefault(casNo, extractChemicalName(chunks));
            KnowledgeDocument document = upsertDocument(
                    KnowledgeDocType.MSDS, casNo, chemicalName == null ? casNo : chemicalName,
                    casNo, resource.getDescription(), markdown);
            for (MsdsChunker.Chunk parsed : chunks) {
                // 块前挂上化学品名：第 4 节「急救措施」正文里通常不出现「甲醇」二字，
                // 不挂名字的话，问「甲醇误食」时这一块只能靠运气被向量路捞回来
                String content = chemicalName == null
                        ? parsed.content()
                        : "[" + chemicalName + "] " + parsed.content();
                String textHash = MsdsChunker.sha256(content);

                Deque<MsdsChunk> candidates = reusableByHash.get(key(casNo, textHash));
                MsdsChunk entity = candidates == null ? null : candidates.pollFirst();
                if (entity != null) {
                    // 内容没变，只是位置可能挪了：更新位置，保留 vector 和 indexedAt
                    entity.setSectionNo(parsed.sectionNo());
                    entity.setSectionTitle(parsed.sectionTitle());
                    entity.setPartNo(parsed.partNo());
                    entity.setChemicalId(chemicalIds.get(casNo));
                    entity.setDocumentId(document.getId());
                    entity.setDocType(KnowledgeDocType.MSDS.name());
                    reused++;
                } else {
                    entity = MsdsChunk.builder()
                            .casNo(casNo)
                            .documentId(document.getId())
                            .docType(KnowledgeDocType.MSDS.name())
                            .sectionNo(parsed.sectionNo())
                            .sectionTitle(parsed.sectionTitle())
                            .partNo(parsed.partNo())
                            .content(content)
                            .textHash(textHash)
                            .chemicalId(chemicalIds.get(casNo))
                            .build();
                    created++;
                }
                current.add(entity);
            }
        }

        // 国标与 SOP：同一套块表，切法与引用单位按 doc_type 分化
        for (KnowledgeDocType type : List.of(KnowledgeDocType.GB, KnowledgeDocType.SOP)) {
            DocumentChunker chunker = chunkers.of(type);
            for (Resource resource : knowledgeResources(type)) {
                String filename = resource.getFilename();
                String sourceCode = filename.substring(0, filename.length() - ".md".length());
                String markdown = read(resource, filename);
                List<TextChunk> parsedChunks = chunker.split(markdown, sourceCode);
                if (parsedChunks.isEmpty()) {
                    log.warn("知识文档没有解析出任何块，已跳过: {}/{}", type, filename);
                    continue;
                }
                files++;

                String title = extractTitle(markdown, sourceCode);
                KnowledgeDocument document = upsertDocument(
                        type, sourceCode, title, null, resource.getDescription(), markdown);
                // partNo 在文档内连续编号：条款号不写进 section_no（列是 NOT NULL），
                // 用连续 partNo 保证库里读取顺序稳定，也让排序不依赖条款号的字典序
                int partNo = 0;
                for (TextChunk parsed : parsedChunks) {
                    String content = "[" + title + "] " + parsed.content();
                    String textHash = MsdsChunker.sha256(content);

                    Deque<MsdsChunk> candidates = reusableByHash.get(key(sourceCode, textHash));
                    MsdsChunk entity = candidates == null ? null : candidates.pollFirst();
                    if (entity != null) {
                        entity.setDocumentId(document.getId());
                        entity.setDocType(type.name());
                        entity.setClauseNo(parsed.clauseNo());
                        entity.setSectionNo(0);
                        entity.setSectionTitle(truncateTitle(parsed.sectionTitle()));
                        entity.setPartNo(partNo++);
                        entity.setChemicalId(null);
                        reused++;
                    } else {
                        entity = MsdsChunk.builder()
                                .casNo(sourceCode)
                                .documentId(document.getId())
                                .docType(type.name())
                                .clauseNo(parsed.clauseNo())
                                .sectionNo(0)
                                .sectionTitle(truncateTitle(parsed.sectionTitle()))
                                .partNo(partNo++)
                                .content(content)
                                .textHash(textHash)
                                .build();
                        created++;
                    }
                    current.add(entity);
                }
            }
        }

        List<MsdsChunk> stale = reusableByHash.values().stream()
                .flatMap(Deque::stream)
                .toList();
        if (!stale.isEmpty()) {
            chunkRepository.deleteAllInBatch(stale);
        }

        List<MsdsChunk> saved = chunkRepository.saveAll(current);

        RebuildResult result = new RebuildResult(files, saved.size(), reused, created, stale.size());
        log.info("MSDS 索引重建完成: {}", result);
        return result;
    }

    /** 库里有数据但内存索引为空（例如重启后未重建）时，直接从库里恢复索引，不再扫文件 */
    public void refreshFromDatabase() {
        List<MsdsChunk> chunks = chunkRepository.findAllByOrderByCasNoAscSectionNoAscPartNoAsc();
        Map<String, String> namesByCas = new HashMap<>();
        chemicalRepository.findAll()
                .forEach(chemical -> namesByCas.put(chemical.getCasNo(), chemical.getName()));
        for (MsdsChunk chunk : chunks) {
            Matcher matcher = SUBJECT_PREFIX.matcher(chunk.getContent());
            if (matcher.find()) {
                namesByCas.putIfAbsent(chunk.getCasNo(), matcher.group(1));
            }
        }
        literalIndex = MsdsLiteralIndex.build(chunks, namesByCas);
    }

    public MsdsLiteralIndex literalIndex() {
        return literalIndex;
    }

    /**
     * 该试剂是否已索引任何 MSDS 块。
     *
     * 调用方用它区分「库里没有这份 MSDS」与「这次查询只是没命中」：
     * 前者应明确告诉用户没有资料，后者才该返回空结果或降级提示。
     */
    public boolean hasChunksFor(String casNo) {
        return literalIndex.containsChemical(casNo);
    }

    private List<Resource> msdsResources() {
        try {
            Resource[] resources = resolver.getResources(MSDS_PATTERN);
            // 顺序固定，重建结果可复现；文件系统返回顺序不保证
            Arrays.sort(resources, (a, b) -> String.valueOf(a.getFilename()).compareTo(String.valueOf(b.getFilename())));
            return List.of(resources);
        } catch (IOException e) {
            throw new UncheckedIOException("扫描 MSDS 目录失败", e);
        }
    }

    private List<Resource> knowledgeResources(KnowledgeDocType type) {
        String pattern = "classpath*:knowledge/" + type.name().toLowerCase() + "/*.md";
        try {
            Resource[] resources = resolver.getResources(pattern);
            Arrays.sort(resources, (a, b) -> String.valueOf(a.getFilename()).compareTo(String.valueOf(b.getFilename())));
            return List.of(resources);
        } catch (IOException e) {
            throw new UncheckedIOException("扫描 " + type + " 目录失败", e);
        }
    }

    private static String read(Resource resource, String filename) {
        try {
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取知识文档失败: " + filename, e);
        }
    }

    /** 文档标题取第一个一级标题；取不到就用文件名，保证文档表里没有空标题 */
    private static String extractTitle(String markdown, String fallback) {
        Matcher matcher = TITLE_PATTERN.matcher(markdown);
        return matcher.find() ? matcher.group(1).trim() : fallback;
    }

    /** section_title 列只有 64 字符，条款标题可能更长；截断而不是让整次重建失败 */
    private static String truncateTitle(String title) {
        if (title == null) {
            return "";
        }
        return title.length() <= 64 ? title : title.substring(0, 64);
    }

    private KnowledgeDocument upsertDocument(KnowledgeDocType type, String sourceCode, String title,
                                             String casNo, String sourcePath, String markdown) {
        LocalDateTime now = LocalDateTime.now();
        KnowledgeDocument document = documentRepository.findBySourceCode(sourceCode)
                .orElseGet(() -> KnowledgeDocument.builder()
                        .sourceCode(sourceCode)
                        .createdAt(now)
                        .build());
        document.setDocType(type);
        document.setTitle(title.length() <= 200 ? title : title.substring(0, 200));
        document.setCasNo(casNo);
        document.setSourcePath(sourcePath);
        document.setContentHash(MsdsChunker.sha256(markdown));
        document.setUpdatedAt(now);
        return documentRepository.save(document);
    }

    /** 从第 1 节里取中文名称。取不到也不影响索引，只是该试剂的问题少一条线索 */
    private static String extractChemicalName(List<MsdsChunker.Chunk> chunks) {
        return chunks.stream()
                .filter(chunk -> chunk.sectionNo() == 1)
                .findFirst()
                .map(chunk -> NAME_PATTERN.matcher(chunk.content()))
                .filter(Matcher::find)
                .map(matcher -> matcher.group(1).trim())
                .orElse(null);
    }

    private static String key(String casNo, String textHash) {
        return casNo + "|" + textHash;
    }
}
