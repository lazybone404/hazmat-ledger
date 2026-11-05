package com.lazybone.hazmat.repository;

import com.lazybone.hazmat.entity.ChemicalIncompatibility;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 禁忌混放关系。
 *
 * 不做 {@code findByCasAOrCasB} 之类的数据库查询：规则表是参考数据，
 * 量级在几十到几千条，一次全读进内存建哈希表比逐对查库更快，
 * 也让「哪些组合没覆盖」能在同一份快照里一次算清。
 */
public interface ChemicalIncompatibilityRepository
        extends JpaRepository<ChemicalIncompatibility, Long> {

    /** 全量顺序读，种子灌入与测试断言用 */
    List<ChemicalIncompatibility> findAllByOrderByCasAAscCasBAsc();

    boolean existsByCasAAndCasB(String casA, String casB);
}
