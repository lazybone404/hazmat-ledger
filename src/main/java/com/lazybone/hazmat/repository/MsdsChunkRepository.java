package com.lazybone.hazmat.repository;

import com.lazybone.hazmat.entity.MsdsChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MsdsChunkRepository extends JpaRepository<MsdsChunk, Long> {

    /**
     * 固定顺序让重建结果可复现。编码进度、失败数这类统计在内存里算：
     * 只有几百行，为每种状态各写一个 count 查询反而多一套要维护的 SQL。
     */
    List<MsdsChunk> findAllByOrderByCasNoAscSectionNoAscPartNoAsc();
}
