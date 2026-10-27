package com.lazybone.hazmat;

import com.lazybone.hazmat.common.ApiResponse;
import com.lazybone.hazmat.common.GlobalExceptionHandler;
import com.lazybone.hazmat.common.ServiceUnavailableException;
import com.lazybone.hazmat.report.ReportMapper;
import com.lazybone.hazmat.report.ReportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.MyBatisSystemException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 报表的资源类失败必须译成 503，而不是 500。
 *
 * 这条路径在本地几乎不可复现——种子数据只有十几条试剂，聚合查询几毫秒就结束。
 * 造真实超时要几千行数据加一个慢查询，既慢又不稳定，所以用 mock 直接构造
 * 「SQL 超时」「连接池等不到连接」这两种异常，验证的是**翻译逻辑**是否正确：
 * 真实数据的量级变了以后，走的还是同一条翻译路径。
 *
 * 这是单元测试而不是 {@code @SpringBootTest}：被测的是 ReportService 的异常分类，
 * 不需要数据库、安全过滤器和 Flyway 一起参与。
 */
class ReportTimeoutTest {

    private final ReportMapper mapper = mock(ReportMapper.class);
    private final ReportService service = new ReportService(mapper);

    @Test
    @DisplayName("SQL 查询超时：503，且异常链里包了几层也能识别")
    void sqlTimeoutBecomesServiceUnavailable() {
        // 真实链路是 MyBatis-Spring 包一层、MyBatis 再包一层，最里面才是 JDBC 异常
        when(mapper.stockSummary(any(), any())).thenThrow(
                new MyBatisSystemException(
                        new org.apache.ibatis.exceptions.PersistenceException(
                                new SQLTimeoutException("query timeout"))));

        assertThatThrownBy(() -> service.stockSummary(30))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("超时");
    }

    @Test
    @DisplayName("连接池等不到连接：同样是 503，不是 500")
    void connectionPoolExhaustionBecomesServiceUnavailable() {
        when(mapper.flowTrend(any(LocalDateTime.class))).thenThrow(
                new CannotGetJdbcConnectionException(
                        "HikariPool-1 - Connection is not available, request timed out after 3000ms.",
                        new SQLTransientConnectionException("connection is not available")));

        assertThatThrownBy(() -> service.flowTrend(7))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    @DisplayName("真的写错了 SQL：继续往上抛，交给兜底分支变 500 并打完整堆栈")
    void otherSqlErrorsAreNotMasked() {
        SQLException syntaxError = new SQLException("Syntax error in SQL statement");
        when(mapper.stockSummary(any(), any())).thenThrow(
                new MyBatisSystemException(
                        new org.apache.ibatis.exceptions.PersistenceException(syntaxError)));

        assertThatThrownBy(() -> service.stockSummary(30))
                .isInstanceOf(MyBatisSystemException.class)
                .as("把 SQL 错误也吞成 503，会让真正的 bug 永远没人修");
    }

    @Test
    @DisplayName("ServiceUnavailableException 由全局处理器译成 503，响应体仍是统一结构")
    void unavailableIsMappedTo503() {
        ResponseEntity<ApiResponse<Void>> response = new GlobalExceptionHandler()
                .handleUnavailable(new ServiceUnavailableException("库存汇总查询超时"));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(503);
        assertThat(response.getBody().data()).isNull();
    }
}
