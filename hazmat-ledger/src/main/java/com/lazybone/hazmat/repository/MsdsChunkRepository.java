package com.lazybone.hazmat.repository;

import com.lazybone.hazmat.entity.MsdsChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MsdsChunkRepository extends JpaRepository<MsdsChunk, Long> {

    /**
     * 固定顺序让重建结果可复现。编码进度、失败数这类统计在内存里算：
     * 只有几百行，为每种状态各写一个 count 查询反而多一套要维护的 SQL。
     *
     * <p>版本化之后带版本重载：检索与编码只能看 active 版本，跨版本全表查会把
     * 旧块也算进来（重建后行数翻倍，向量路会重复召回同一段正文）。
     */
    List<MsdsChunk> findAllByOrderByCasNoAscSectionNoAscPartNoAsc();

    List<MsdsChunk> findByIndexVersionOrderByCasNoAscSectionNoAscPartNoAsc(String indexVersion);

    /** 版本化为空的历史数据（升级前落库的块），在首次版本化重建前仍要可读 */
    List<MsdsChunk> findByIndexVersionIsNullOrderByCasNoAscSectionNoAscPartNoAsc();
}
