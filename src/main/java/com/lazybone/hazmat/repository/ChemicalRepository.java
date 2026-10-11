package com.lazybone.hazmat.repository;

import com.lazybone.hazmat.entity.Chemical;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

/**
 * 试剂档案。
 *
 * 列表查询走 {@link JpaSpecificationExecutor}：名称/CAS 关键词、分类、危险等级
 * 是可选的组合条件，用固定方法名会写出 8 个 findBy 变体，还要为「关键词里的
 * {@code %} 怎么转义」在每个方法里重写一遍。
 */
public interface ChemicalRepository extends JpaRepository<Chemical, Long>,
        JpaSpecificationExecutor<Chemical> {

    Optional<Chemical> findByCasNo(String casNo);

    boolean existsByCasNo(String casNo);

    /** 供种子数据与需要全量遍历的场景使用；对外的列表接口已改为分页 */
    List<Chemical> findAllByOrderByIdAsc();
}
