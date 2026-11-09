package com.lazybone.hazmat.repository;

import com.lazybone.hazmat.entity.MsdsChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /**
     * 安全摘要：指定版本、指定 CAS、指定章节的 MSDS 块。
     *
     * <p>带 {@code indexVersion} 而不是全表查，理由同 {@link #activeChunks()}：
     * 重建后的旧块还在表里，不按版本过滤会把两版正文拼在一起。
     * 只取 {@code doc_type=MSDS}，国标/SOP 的 sectionNo 语义不同，不能混进来。
     */
    @Query("""
            select c from MsdsChunk c
             where c.docType = 'MSDS'
               and c.casNo = :casNo
               and c.sectionNo in :sectionNos
               and c.indexVersion = :version
             order by c.sectionNo asc, c.partNo asc
            """)
    List<MsdsChunk> findSectionsInVersion(@Param("casNo") String casNo,
                                          @Param("sectionNos") Collection<Integer> sectionNos,
                                          @Param("version") String version);

    /** 同上，但读的是版本化之前落库的历史块 */
    @Query("""
            select c from MsdsChunk c
             where c.docType = 'MSDS'
               and c.casNo = :casNo
               and c.sectionNo in :sectionNos
               and c.indexVersion is null
             order by c.sectionNo asc, c.partNo asc
            """)
    List<MsdsChunk> findSectionsWithoutVersion(@Param("casNo") String casNo,
                                               @Param("sectionNos") Collection<Integer> sectionNos);
}
