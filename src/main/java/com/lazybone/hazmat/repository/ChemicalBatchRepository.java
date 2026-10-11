package com.lazybone.hazmat.repository;

import com.lazybone.hazmat.entity.ChemicalBatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 批次库存。这个接口里最重要的不是方法名，是**为什么这两个写方法长这样**。
 */
public interface ChemicalBatchRepository extends JpaRepository<ChemicalBatch, Long> {

    List<ChemicalBatch> findByChemicalIdOrderByExpireDateAsc(Long chemicalId);

    /**
     * 入库前的批次号查重，走 {@code (chemical_id, batch_no)} 唯一索引。
     *
     * <b>不要退回「查出该试剂的全部批次再 anyMatch」。</b>那是 O(n)：压测时
     * 同一试剂从 10 个批次涨到 7900 个，入库吞吐从 377 QPS 掉到 114 QPS，
     * p95 从 40 ms 涨到 100 ms；而换一个只有 10 个批次的新试剂立刻回到 344 QPS
     * ——退化只与单个试剂的批次数有关，与全库数据量无关。
     * 这条查询是索引点查，成本与批次数无关。
     *
     * 唯一索引同时兜住并发：两个请求都通过了这条检查时，后插入的那个会撞索引。
     */
    boolean existsByChemicalIdAndBatchNo(Long chemicalId, String batchNo);

    /**
     * 全部还有库存的批次，盘点建单用。
     *
     * 条件只看 {@code availableQuantity > 0}，<b>不</b>过滤效期：已经过期但瓶里还有东西，
     * 它仍然占着柜子、仍然要盘。效期只影响能不能出库，不影响实物存不存在。
     */
    @Query("select b from ChemicalBatch b where b.availableQuantity > 0 order by b.chemicalId, b.id")
    List<ChemicalBatch> findAllWithStock();

    /** 同上，限定在指定试剂范围内（按柜子/品类分批盘时用） */
    @Query("""
            select b from ChemicalBatch b
             where b.availableQuantity > 0
               and b.chemicalId in :chemicalIds
             order by b.chemicalId, b.id
            """)
    List<ChemicalBatch> findAllWithStockByChemicalIds(@Param("chemicalIds") List<Long> chemicalIds);

    /**
     * 出库扣减。先到期先出（FEFO），调用方按 expireDate 升序拿批次后逐个扣。
     *
     * 关键在于 {@code and b.availableQuantity >= :qty} 这个条件：
     * 它让「判断够不够」和「扣掉」变成**同一条语句**，由数据库在执行时原子地一起完成。
     * 返回受影响行数——0 就代表当时不够，而不是「我先查过够、结果扣的时候不够了」。
     *
     * 为什么不用「先 findById 看余量，再 set 新值保存」：那两个动作之间有窗口，
     * 两个请求都能查到余量够，然后都去扣，就超发了。这就是经典的丢失更新。
     *
     * 为什么不用 {@code @Version} 乐观锁：扣减**不需要读**。版本号方案要先读一次、
     * 改完再比对版本，多一次往返，窗口还更长。条件更新是把判断推给数据库，
     * 一步到位。两者适用场景不同，不是谁更先进。
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ChemicalBatch b
               set b.availableQuantity = b.availableQuantity - :qty
             where b.id = :id
               and b.availableQuantity >= :qty
            """)
    int deduct(@Param("id") Long id, @Param("qty") BigDecimal qty);

    /**
     * 读出当前余量。
     *
     * <b>必须是标量查询</b>，不能写成返回实体的 findBy。原因是批量 update 不会同步
     * 持久化上下文里已经加载过的实体，写成返回实体会被 Hibernate 的缓存命中，
     * 拿回扣减**之前**的旧值——而且不报错，只是流水里的余额悄悄记错。
     * 标量查询直接走 ResultSet，绕开这一层。
     */
    @Query("select b.availableQuantity from ChemicalBatch b where b.id = :id")
    BigDecimal currentQuantity(@Param("id") Long id);

    /** 入库加回。加法天然原子，不需要条件 */
    @Modifying
    @Query("update ChemicalBatch b set b.availableQuantity = b.availableQuantity + :qty where b.id = :id")
    int addStock(@Param("id") Long id, @Param("qty") BigDecimal qty);

    /**
     * 盘点调账：把可用量**设成实盘量**，前提是账面量仍然是盘点单建单时的值。
     *
     * <b>为什么条件里要有 {@code b.availableQuantity = :book}：</b>
     * 盘点的语义是「相对于我数的那一刻」。如果单子在 DRAFT/SUBMITTED 期间
     * 有人出库、入库或报废，账面量已经变了，实盘量就不再是同一个基准上的数——
     * 这时候覆盖成实盘量会把期间发生的业务量悄悄吞掉。
     * 返回 0 就代表「盘点期间库存动过」，调用方必须拒绝整张单并要求重盘。
     *
     * <b>为什么不是 {@code + diff}：</b>加法要求调用方先算对差异，而差异是
     * 实盘减账面，两个数都在调用方手里。直接设成实盘量少一次运算，
     * 而且结果与实盘完全一致，不会因为中间某次读取拿到旧值而偏一毫。
     *
     * 和 {@link #deduct} 一样用 {@code @Modifying(flushAutomatically = true)}：
     * 需要先把持久化上下文里挂着的改动刷下去，再执行批量更新，顺序才不会乱。
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ChemicalBatch b
               set b.availableQuantity = :actual
             where b.id = :id
               and b.availableQuantity = :book
            """)
    int adjustTo(@Param("id") Long id,
                 @Param("book") BigDecimal book,
                 @Param("actual") BigDecimal actual);

    /**
     * 开封时只留痕、不改可用期（档案没配 {@code postOpenDays}）。
     *
     * <b>{@code and b.openedAt is null} 是这条 SQL 存在的原因。</b>Service 里虽然已经
     * 读过一遍 {@code isOpened()}，但「读—判断—写」之间有空档：两个并发请求都能读到
     * 「未开封」，然后都写成功，第二个把 {@code openedAt/openedBy} 覆盖掉。
     * 把「未开封」做进 WHERE，判断与写入就由数据库原子完成，返回 0 就是被别人抢先开过了。
     * 和扣库存的 {@code deduct} 是同一个道理，只是这里保护的不是数量而是状态。
     *
     * {@code clearAutomatically}：批量更新绕过持久化上下文，Service 需要重新读出更新后的行，
     * 不清缓存会拿到改之前的实体。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChemicalBatch b
               set b.openedAt = :openedAt,
                   b.openedBy = :operator
             where b.id = :id
               and b.openedAt is null
            """)
    int markOpened(@Param("id") Long id,
                   @Param("openedAt") LocalDateTime openedAt,
                   @Param("operator") String operator);

    /** 同上，且把可用截止日提前到 {@code usableUntil} */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChemicalBatch b
               set b.openedAt = :openedAt,
                   b.openedBy = :operator,
                   b.usableUntil = :usableUntil
             where b.id = :id
               and b.openedAt is null
            """)
    int markOpenedShortening(@Param("id") Long id,
                             @Param("openedAt") LocalDateTime openedAt,
                             @Param("operator") String operator,
                             @Param("usableUntil") LocalDate usableUntil);

    /**
     * 某试剂当前**可出库**的批次，按可用截止日升序。
     *
     * 过滤条件用 {@code usableUntil} 而不是 {@code expireDate}：开封后可用期可能提前，
     * 一瓶开封三个月、包装有效期还有半年的甲醇，按 expireDate 会被当成可发。
     * 过滤条件不能省，而且它是踩出来的：一开始只写「有货」+ 按效期升序，
     * 结果过期批次恰好排第一，被优先发给了实验室（冒烟测试打时间线时才发现）。
     * 临期要赶紧用，过期只能报废，排序与过滤是两个概念。
     */
    @Query("""
            select b from ChemicalBatch b
             where b.chemicalId = :chemicalId
               and b.availableQuantity > 0
               and b.usableUntil >= :today
             order by b.usableUntil asc, b.id asc
            """)
    List<ChemicalBatch> findAvailableBatches(@Param("chemicalId") Long chemicalId,
                                             @Param("today") LocalDate today);

    /** 某试剂的台账总量（含已过期），用于对账和展示 */
    @Query("select coalesce(sum(b.availableQuantity), 0) from ChemicalBatch b where b.chemicalId = :chemicalId")
    BigDecimal sumAvailable(@Param("chemicalId") Long chemicalId);

    /** 某试剂真正**可用**的量：只算未过可用期的。库存视图上的「可用」用这个 */
    @Query("""
            select coalesce(sum(b.availableQuantity), 0) from ChemicalBatch b
             where b.chemicalId = :chemicalId and b.usableUntil >= :today
            """)
    BigDecimal sumUsable(@Param("chemicalId") Long chemicalId, @Param("today") LocalDate today);

    /**
     * 效期预警：{@code threshold} 之前到可用期、且还有余量的批次（含已过期）。
     *
     * 按 {@code usableUntil} 筛：开封后只剩几天的批次必须出现在预警里，
     * 否则「还有半年的包装有效期」会把它藏起来，而它才是真正急需处理的那批。
     *
     * 不写 order by，排序交给调用方传入的 {@link Pageable}——分页查询里
     * 静态 order by 与 Pageable 的 Sort 同时存在时，以后者为准，
     * 写在两个地方只会让读代码的人以为第一个生效。
     */
    @Query("""
            select b from ChemicalBatch b
             where b.availableQuantity > 0
               and b.usableUntil <= :threshold
            """)
    Page<ChemicalBatch> findExpiring(@Param("threshold") LocalDate threshold, Pageable pageable);
}
