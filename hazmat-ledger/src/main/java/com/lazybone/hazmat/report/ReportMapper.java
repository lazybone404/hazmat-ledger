package com.lazybone.hazmat.report;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 只读报表查询。
 *
 * <b>这里是 MyBatis-Plus 在本项目里的全部用途</b>：GROUP BY、CASE WHEN、
 * 多表 LEFT JOIN 之后再聚合。这类 SQL 用 JPA 也能写（原生查询 + 投影接口），
 * 但 SQL 会被拆成字符串常量与接口方法两半；用 XML 写则整条语句可读、可单独执行、
 * 可以直接贴进数据库客户端核对。
 *
 * 边界（不要越过）：
 * <ul>
 *   <li>只读：这里没有 insert/update/delete，写路径一律留在 JPA 的条件更新与乐观锁里。</li>
 *   <li>不映射实体：只返回报表自己的 Row 对象，避免同一个表出现两套字段映射。</li>
 *   <li>不参与事务边界：报表查询是单条 SELECT，不需要和业务事务混在一起。</li>
 * </ul>
 */
@Mapper
public interface ReportMapper {

    /**
     * 按「分类 + 单位」汇总库存与效期分布。
     *
     * @param today     今天，用于判定已过期
     * @param threshold 预警上界（今天 + N 天），含当天
     */
    List<StockSummaryRow> stockSummary(@Param("today") LocalDate today,
                                       @Param("threshold") LocalDate threshold);

    /** 自 {@code since} 起按「日期 + 变更类型」汇总流水 */
    List<FlowTrendRow> flowTrend(@Param("since") LocalDateTime since);
}
