package com.lazybone.hazmat.service;

import com.lazybone.hazmat.common.BusinessException;
import com.lazybone.hazmat.dto.ChemicalSafetyOverview;
import com.lazybone.hazmat.dto.ChemicalSafetyView;
import com.lazybone.hazmat.entity.Chemical;
import com.lazybone.hazmat.entity.MsdsChunk;
import com.lazybone.hazmat.msds.MsdsIndexService;
import com.lazybone.hazmat.msds.MsdsSearchService;
import com.lazybone.hazmat.msds.SearchFilter;
import com.lazybone.hazmat.repository.MsdsChunkRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 台账与知识库的连接点：从试剂 id 出发，限定 CAS 号检索 MSDS。
 *
 * 为什么不把这段放进 {@link ChemicalService}：档案服务只该依赖档案与缓存，
 * 让它去依赖检索服务，会让「查一个试剂」这种基础读操作也背上检索模块的故障面。
 * 反过来把检索依赖收在这个窄服务里，台账主线的构造与测试都不受影响。
 *
 * 不标 {@code @Transactional}：档案读取走缓存、检索是内存扫描加一次可能的
 * embedding 调用，没有需要跨语句一致性的写操作。
 */
@Service
public class ChemicalSafetyService {

    /** 与 {@link MsdsSearchService} 的查询长度边界保持一致；检索服务内部还会再校验一次 */
    private static final int MAX_QUERY_LENGTH = 200;

    /**
     * 安全摘要固定取的四节：危险性概述、急救措施、泄漏应急处理、接触控制/个体防护。
     * GB/T 16483 的节号是固定的，所以这里不需要相关性检索。
     */
    private static final List<Integer> OVERVIEW_SECTIONS = List.of(2, 4, 6, 8);

    private static final Map<Integer, String> SECTION_TITLES = Map.of(
            2, "危险性概述",
            4, "急救措施",
            6, "泄漏应急处理",
            8, "接触控制/个体防护");

    /** 摘要正文上限。详情页要能一眼看完，完整内容仍可在检索里查 */
    private static final int MAX_SECTION_LENGTH = 500;

    private final ChemicalService chemicalService;
    private final MsdsSearchService searchService;
    private final MsdsIndexService indexService;
    private final MsdsChunkRepository chunkRepository;

    public ChemicalSafetyService(ChemicalService chemicalService,
                                 MsdsSearchService searchService,
                                 MsdsIndexService indexService,
                                 MsdsChunkRepository chunkRepository) {
        this.chemicalService = chemicalService;
        this.searchService = searchService;
        this.indexService = indexService;
        this.chunkRepository = chunkRepository;
    }

    public ChemicalSafetyView safety(Long id, String query, int topK) {
        return safety(id, query, null, topK);
    }

    public ChemicalSafetyView safety(Long id, String query, String docType, int topK) {
        // 先取档案：id 不存在时应该是 404，而不是「查询为空」的 400
        Chemical chemical = chemicalService.findById(id);

        if (query == null || query.isBlank()) {
            throw new BusinessException("检索内容不能为空");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new BusinessException("检索内容最长 " + MAX_QUERY_LENGTH + " 个字符");
        }

        // 没有索引过这份 MSDS 时直接返回 available=false。
        // 不能先走检索再按空结果判断：那会在配置了云端模型时白花一次 embedding 调用，
        // 而且「检索为空」与「没有资料」对用户是两件不同的事。
        if (!indexService.hasChunksFor(chemical.getCasNo())) {
            return ChemicalSafetyView.unavailable(chemical, query);
        }

        return ChemicalSafetyView.available(chemical, query,
                searchService.searchFiltered(query, SearchFilter.of(chemical.getCasNo(), docType), topK));
    }

    /**
     * 领用详情页的安全摘要：固定四节，不做检索。
     *
     * <p>为什么按版本查、并且只查一次：重建索引时旧块还在表里，不按 active 版本过滤
     * 会把两版正文拼在一起；而四节一次性取回，前端不必为四个章节发四次请求。
     *
     * <p>有索引但缺某几节时返回 {@code available=true} 和明确的缺失说明——
     * 「资料不完整」与「没有资料」是两件事，不能都退化成空列表。
     */
    public ChemicalSafetyOverview overview(Long chemicalId) {
        Chemical chemical = chemicalService.findById(chemicalId);
        if (!indexService.hasChunksFor(chemical.getCasNo())) {
            return ChemicalSafetyOverview.unavailable(chemical);
        }

        String version = indexService.activeVersion();
        List<MsdsChunk> chunks = version == null
                ? chunkRepository.findSectionsWithoutVersion(chemical.getCasNo(), OVERVIEW_SECTIONS)
                : chunkRepository.findSectionsInVersion(chemical.getCasNo(), OVERVIEW_SECTIONS, version);

        // 同一节可能被切成多块，按 partNo 顺序拼回去；用 LinkedHashMap 保持节号升序
        Map<Integer, StringBuilder> merged = new LinkedHashMap<>();
        Map<Integer, String> titles = new LinkedHashMap<>();
        for (MsdsChunk chunk : chunks) {
            merged.computeIfAbsent(chunk.getSectionNo(), k -> new StringBuilder())
                    .append(chunk.getContent()).append(' ');
            titles.putIfAbsent(chunk.getSectionNo(), chunk.getSectionTitle());
        }

        List<ChemicalSafetyOverview.Section> sections = new ArrayList<>();
        for (Map.Entry<Integer, StringBuilder> entry : merged.entrySet()) {
            int sectionNo = entry.getKey();
            String content = entry.getValue().toString().trim();
            if (content.length() > MAX_SECTION_LENGTH) {
                content = content.substring(0, MAX_SECTION_LENGTH) + "…";
            }
            String title = titles.get(sectionNo);
            if (title == null || title.isBlank()) {
                title = SECTION_TITLES.getOrDefault(sectionNo, "第 " + sectionNo + " 节");
            }
            sections.add(new ChemicalSafetyOverview.Section(sectionNo, title, content));
        }

        List<Integer> missing = OVERVIEW_SECTIONS.stream()
                .filter(no -> !merged.containsKey(no))
                .toList();
        String message = missing.isEmpty() ? null
                : "已索引该试剂的 MSDS，但缺少以下章节："
                + missing.stream().map(no -> SECTION_TITLES.getOrDefault(no, "第 " + no + " 节"))
                .reduce((a, b) -> a + "、" + b).orElse("");

        return new ChemicalSafetyOverview(true, chemical.getId(), chemical.getCasNo(),
                chemical.getName(), chemical.getUnit(), sections, message);
    }
}
