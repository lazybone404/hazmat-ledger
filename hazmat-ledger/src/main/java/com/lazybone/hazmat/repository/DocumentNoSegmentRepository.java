package com.lazybone.hazmat.repository;

import com.lazybone.hazmat.entity.DocumentNoSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface DocumentNoSegmentRepository extends JpaRepository<DocumentNoSegment, String> {

    /**
     * 原子推进号段并返回受影响行数。{@code next_value = next_value + block} 由数据库自己算，
     * 不在应用里「读出来加完再写回去」，因此两个事务同时执行时不会拿到同一段。
     * 单条 UPDATE 会持有该行的写锁直到事务结束，紧接着的读取能看到自己刚推进的值。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update DocumentNoSegment s set s.nextValue = s.nextValue + :block, s.updatedAt = :now "
            + "where s.prefix = :prefix")
    int advance(@Param("prefix") String prefix, @Param("block") int block, @Param("now") LocalDateTime now);

    /**
     * 在 {@link #advance} 之后读取推进后的值。必须与 advance 在同一个事务里：
     * 该事务持有行锁，读到的是自己刚写入的值。
     */
    @Query("select s.nextValue from DocumentNoSegment s where s.prefix = :prefix")
    Optional<Long> findNextValue(@Param("prefix") String prefix);

    /**
     * 首次建行。用原生 INSERT 而不是 {@code save()}：给主键赋值后 Spring Data 会走
     * {@code merge}（先查再决定插入还是更新），行不存在时被并发插入会退化成 UPDATE，
     * 用旧值覆盖掉别人刚推进的号段。原生 INSERT 的角色明确——要么插入成功，
     * 要么撞主键抛异常交给上层重试。
     */
    @Modifying
    @Query(value = "insert into document_no_segment (prefix, next_value, updated_at) "
            + "values (:prefix, :nextValue, :now)", nativeQuery = true)
    int insertRow(@Param("prefix") String prefix, @Param("nextValue") long nextValue,
                  @Param("now") LocalDateTime now);
}
