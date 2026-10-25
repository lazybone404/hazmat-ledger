package com.lazybone.hazmat.report;

import com.lazybone.hazmat.common.ServiceUnavailableException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;

import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

/**
 * 报表查询。这个类里几乎只有参数换算，刻意不再包一层 DTO：
 * 报表的字段就是 SQL 的语义，多转一层只会让「改一个列名要改三个文件」。
 *
 * 不加 {@code @Transactional}：单条 SELECT，让它自己拿连接、用完立刻还。
 * 报表的聚合可能扫全表，若放进长事务，其它写请求会被连接池和锁拖慢。
 */
@Service
public class ReportService {

    /**
     * 异常原因链的遍历深度上限。
     *
     * 超时异常一定在 cause 链上，但 Spring 与 MyBatis 各自会包一两层
     * （{@code MyBatisSystemException} → {@code PersistenceException} → {@code SQLException}）。
     * 设上限是为了防御异常对象互相引用成环——那种情况下不设界会死循环。
     */
    private static final int MAX_CAUSE_DEPTH = 10;

    private final ReportMapper reportMapper;

    public ReportService(ReportMapper reportMapper) {
        this.reportMapper = reportMapper;
    }

    public List<StockSummaryRow> stockSummary(int withinDays) {
        LocalDate today = LocalDate.now();
        return guard("库存汇总",
                () -> reportMapper.stockSummary(today, today.plusDays(withinDays)));
    }

    /**
     * 趋势从「今天往前第 days 天」的 00:00 开始，含今天。
     *
     * 用 {@code minusDays(days - 1)} 而不是 {@code minusDays(days)}：
     * 用户要「近 7 天」时，应该是今天加前面 6 天，共 7 个自然日。
     */
    public List<FlowTrendRow> flowTrend(int days) {
        LocalDate from = LocalDate.now().minusDays(days - 1L);
        return guard("流水趋势", () -> reportMapper.flowTrend(from.atStartOfDay()));
    }

    /**
     * 把「资源类的失败」译成 503，其它异常原样抛出。
     *
     * 报表失败只有两种原因值得区分：查询太慢/连接池被占满（重试或缩小范围可能成功，
     * 监控不该报警）与真的写错了 SQL（必须报警）。前者统一成
     * {@link ServiceUnavailableException}，全局处理器译成 503；
     * 后者继续往上抛，落到兜底分支变 500，日志里带完整堆栈。
     */
    private <T> T guard(String what, Supplier<T> query) {
        try {
            return query.get();
        } catch (RuntimeException e) {
            if (isTemporarilyUnavailable(e)) {
                throw new ServiceUnavailableException(what + "查询超时或连接资源不足，请缩小范围或稍后重试");
            }
            throw e;
        }
    }

    private static boolean isTemporarilyUnavailable(Throwable e) {
        Throwable current = e;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            // SQLTimeoutException：JDBC 的查询超时（H2 与 MySQL 都会抛它）
            // QueryTimeoutException：Spring 的异常翻译器对上面那个的映射结果
            // SQLTransientConnectionException：连接池等不到连接（Hikari connection-timeout）
            if (current instanceof SQLTimeoutException
                    || current instanceof QueryTimeoutException
                    || current instanceof SQLTransientConnectionException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
