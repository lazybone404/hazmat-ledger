package com.lazybone.hazmat.service;

import com.lazybone.hazmat.cache.ChemicalCache;
import com.lazybone.hazmat.common.BusinessException;
import com.lazybone.hazmat.common.PageQuery;
import com.lazybone.hazmat.common.ResourceNotFoundException;
import com.lazybone.hazmat.dto.ChemicalRequest;
import com.lazybone.hazmat.entity.Chemical;
import com.lazybone.hazmat.repository.ChemicalRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 试剂档案。只有一个业务规则（CAS 号不能重复），但**也走一层 Service**。
 *
 * 为什么不省掉这层直接让 Controller 调 Repository：
 * 「CAS 号唯一」是业务规则，不是持久化细节。放 Controller 里，将来加个批量导入入口
 * 就得把它复制一遍；放 Repository 里更糟——它变成了「查库时顺手做的校验」，
 * 别人绕过这个查询方法就绕过了规则。规则应该待在**所有入口都必经的那一层**。
 */
@Service
public class ChemicalService {

    private final ChemicalRepository chemicalRepository;
    private final ChemicalCache chemicalCache;

    public ChemicalService(ChemicalRepository chemicalRepository, ChemicalCache chemicalCache) {
        this.chemicalRepository = chemicalRepository;
        this.chemicalCache = chemicalCache;
    }

    @Transactional
    public Chemical create(ChemicalRequest request) {
        if (chemicalRepository.existsByCasNo(request.casNo())) {
            throw new BusinessException("CAS 号已存在: " + request.casNo());
        }
        Chemical saved = chemicalRepository.save(Chemical.builder()
                .casNo(request.casNo())
                .name(request.name())
                .category(request.category())
                .hazardLevel(request.hazardLevel())
                .unit(request.unit())
                .spec(request.spec())
                .msdsUrl(request.msdsUrl())
                .postOpenDays(request.postOpenDays())
                .build());
        // 建档后顺手预热。因为档案只增不改，这是唯一的写路径，
        // 不存在「改了档案要删缓存」的问题（将来开了编辑接口就必须补）。
        chemicalCache.put(saved);
        return saved;
    }

    /**
     * 按 id 取试剂档案，读路径走缓存。
     *
     * 这里刻意**不标 {@code @Transactional(readOnly = true)}**：缓存命中的请求不需要事务，
     * 给它开一个只读事务等于把一个纯内存操作也算进连接池的占用里。
     * 回源查库本身是一次主键查询，不需要跨语句的读一致视图。
     *
     * 缓存不可用、未命中、命中空值哨兵三种情况在这里收口：前两者回源，
     * 后者直接抛 404——空值哨兵的意义就是让重复的无效 id 不再打到数据库。
     */
    public Chemical findById(Long id) {
        ChemicalCache.Lookup lookup = chemicalCache.lookup(id);
        if (lookup.state() == ChemicalCache.State.HIT) {
            return lookup.chemical();
        }
        if (lookup.state() == ChemicalCache.State.MISSING) {
            throw notFound(id);
        }
        Chemical chemical = chemicalRepository.findById(id).orElse(null);
        if (chemical == null) {
            if (lookup.shouldFill()) {
                chemicalCache.putMissing(id);
            }
            throw notFound(id);
        }
        if (lookup.shouldFill()) {
            chemicalCache.put(chemical);
        }
        return chemical;
    }

    private ResourceNotFoundException notFound(Long id) {
        return new ResourceNotFoundException("试剂不存在: id=" + id);
    }

    /**
     * 分页查询试剂档案。
     *
     * 三个条件都是可选的，用 Specification 动态拼，而不是写一堆 findBy 组合。
     * 关键是**关键词只匹配名称与 CAS**：分类和等级有各自的精确筛选参数，
     * 若关键词也去匹配它们，搜「易燃」会把整个分类都捞出来，反而干扰按名称找东西。
     */
    @Transactional(readOnly = true)
    public Page<Chemical> page(String keyword, String category, String hazardLevel, Pageable pageable) {
        Specification<Chemical> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (StringUtils.hasText(keyword)) {
                String pattern = PageQuery.likeContains(keyword);
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), pattern, PageQuery.LIKE_ESCAPE),
                        cb.like(cb.lower(root.get("casNo")), pattern, PageQuery.LIKE_ESCAPE)));
            }
            if (StringUtils.hasText(category)) {
                predicates.add(cb.equal(root.get("category"), category.trim()));
            }
            if (StringUtils.hasText(hazardLevel)) {
                predicates.add(cb.equal(root.get("hazardLevel"), hazardLevel.trim()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return chemicalRepository.findAll(spec, pageable);
    }
}
