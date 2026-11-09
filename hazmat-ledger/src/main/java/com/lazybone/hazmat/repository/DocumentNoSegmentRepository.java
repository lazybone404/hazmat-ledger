package com.lazybone.hazmat.repository;

import com.lazybone.hazmat.entity.DocumentNoSegment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DocumentNoSegmentRepository extends JpaRepository<DocumentNoSegment, String> {

    /**
     * 锁住号段行再读写，保证两个实例拿到不重叠的区间。
     * 锁随事务提交/回滚释放，不需要额外的解锁路径。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DocumentNoSegment s where s.prefix = :prefix")
    Optional<DocumentNoSegment> findForUpdate(@Param("prefix") String prefix);
}
