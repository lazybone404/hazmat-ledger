package com.lazybone.hazmat;

import com.lazybone.hazmat.dto.ChemicalRequest;
import com.lazybone.hazmat.entity.Chemical;
import com.lazybone.hazmat.service.ChemicalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MySQL 实机集成测试（B5）：验证 H2 覆盖不到的数据库差异。
 *
 * <p>这个类名以 {@code IT} 结尾，默认的 {@code mvn test} 不会包含它；需要显式运行：
 * <pre>
 *   ./mvnw -Dtest=MySqlMigrationIT test
 * </pre>
 * 没装 Docker 时 Testcontainers 会跳过，而不是把构建打红。
 *
 * <p>验证四件事：
 * <ol>
 *   <li>Flyway V1–V10 在 MySQL 8.4 上能按顺序执行完，应用能以 {@code ddl-auto=validate} 启动；</li>
 *   <li>utf8mb4 下中文名称写入/读出不变形；</li>
 *   <li>禁忌关系的 {@code CHECK (cas_a < cas_b)} 与唯一约束在 MySQL 上真的会拦；</li>
 *   <li>分页查询在真实 MySQL 上总数、页大小与排序都正确。</li>
 * </ol>
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("mysql")
@SpringBootTest(properties = {
        "hazmat.seed.enabled=false",
        "hazmat.security.seed-users=false",
        "hazmat.ai.encode-on-startup=false",
        "hazmat.cache.chemical.enabled=false",
        "hazmat.vector.store=memory"
})
class MySqlMigrationIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hazmat_it")
            .withUsername("hazmat")
            .withPassword("hazmat-it-password");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChemicalService chemicalService;

    @Test
    void flywayAppliesEveryMigrationAndV10ColumnsExist() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank",
                String.class);
        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");

        List<String> basisColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'chemical_incompatibility'",
                String.class);
        assertThat(basisColumns).contains(
                "source_type", "source_doc", "source_clause", "source_excerpt", "verification_status");

        // 连接级别也确认一次：迁移没有把库降级成 latin1
        String charset = jdbcTemplate.queryForObject(
                "SELECT default_character_set_name FROM information_schema.schemata "
                        + "WHERE schema_name = DATABASE()", String.class);
        assertThat(charset).isEqualTo("utf8mb4");
    }

    @Test
    void chineseTextRoundTripsWithoutMojibake() {
        Chemical created = chemicalService.create(new ChemicalRequest(
                "67-56-1", "甲醇（色谱纯）", "易燃", "高", "mL", "AR 500mL", null, null));

        String stored = jdbcTemplate.queryForObject(
                "SELECT name FROM chemical WHERE id = ?", String.class, created.getId());
        assertThat(stored).isEqualTo("甲醇（色谱纯）");
        assertThat(chemicalService.findById(created.getId()).getName()).isEqualTo("甲醇（色谱纯）");
    }

    @Test
    void incompatiblePairRejectsSameAndReversedDuplicates() {
        insertRule("7647-01-0", "7664-93-9");

        // 同一方向重复：靠 uk_incompatibility_pair 唯一约束
        assertThatThrownBy(() -> insertRule("7647-01-0", "7664-93-9"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 反方向重复：先撞 CHECK (cas_a < cas_b)，不能依赖应用层排序兜底。
        // MySQL 的 CHECK 违反是通用 SQL 错误（HY000/3819），Spring 不会把它翻译成
        // DataIntegrityViolationException，这里如实断言父类型与约束名
        assertThatThrownBy(() -> insertRule("7664-93-9", "7647-01-0"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_incompatibility_order");
    }

    @Test
    void paginationIsCorrectOnRealMySql() {
        for (int i = 0; i < 5; i++) {
            chemicalService.create(new ChemicalRequest(
                    "60-29-7-" + i, "乙醚样品" + i, "易燃", "中", "mL", "AR 500mL", null, null));
        }

        Page<Chemical> first = chemicalService.page(null, null, null,
                PageRequest.of(0, 2, Sort.by("id")));
        Page<Chemical> second = chemicalService.page(null, null, null,
                PageRequest.of(1, 2, Sort.by("id")));

        assertThat(first.getTotalElements()).isEqualTo(5);
        assertThat(first.getTotalPages()).isEqualTo(3);
        assertThat(first.getContent()).hasSize(2);
        assertThat(second.getContent()).hasSize(2);
        assertThat(second.getContent().get(0).getId())
                .isGreaterThan(first.getContent().get(1).getId());
    }

    private void insertRule(String casA, String casB) {
        jdbcTemplate.update(
                "INSERT INTO chemical_incompatibility "
                        + "(cas_a, cas_b, reason, source, source_type, verification_status, created_at) "
                        + "VALUES (?, ?, ?, ?, 'MSDS', 'VERIFIED', NOW(6))",
                casA, casB, "测试原因", "MSDS 测试");
    }
}
