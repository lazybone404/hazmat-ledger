package com.lazybone.hazmat.service;

import com.lazybone.hazmat.common.BusinessException;
import com.lazybone.hazmat.dto.ChemicalSafetyView;
import com.lazybone.hazmat.entity.Chemical;
import com.lazybone.hazmat.msds.MsdsIndexService;
import com.lazybone.hazmat.msds.MsdsSearchService;
import org.springframework.stereotype.Service;

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

    private final ChemicalService chemicalService;
    private final MsdsSearchService searchService;
    private final MsdsIndexService indexService;

    public ChemicalSafetyService(ChemicalService chemicalService,
                                 MsdsSearchService searchService,
                                 MsdsIndexService indexService) {
        this.chemicalService = chemicalService;
        this.searchService = searchService;
        this.indexService = indexService;
    }

    public ChemicalSafetyView safety(Long id, String query, int topK) {
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
                searchService.search(query, chemical.getCasNo(), topK));
    }
}
