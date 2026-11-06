package com.lazybone.hazmat.msds.vector;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pgvector 实现的门控集成测试：直接建存储、直接断言 SQL 行为，不启动 Spring 上下文。
 *
 * <p>默认不跑（`mvn test` 不依赖 Docker）。先起容器再显式打开：
 * <pre>
 *   docker compose --profile pgvector up -d
 *   HAZMAT_PGVECTOR=true ./mvnw -Dtest=PgVectorStoreTest test
 * </pre>
 *
 * <p>连接参数用 {@code HAZMAT_PG_*} 覆盖，默认对齐 compose 映射到宿主的 5433。
 * 用独立的 {@code msds_vector_test} 表与 8 维小向量：不碰演示数据，也不必为了
 * 测试加载 1024 维模型；维度校验是在构造时读配置，与小维度测试不冲突。
 */
@EnabledIfEnvironmentVariable(named = "HAZMAT_PGVECTOR", matches = "true")
class PgVectorStoreTest {

    private static final int DIM = 8;
    private static final String TABLE = "msds_vector_test";

    private PgVectorStore store;

    private static PgVectorProperties properties(String url) {
        PgVectorProperties properties = new PgVectorProperties();
        properties.setUrl(url);
        properties.setUsername(env("HAZMAT_PG_USERNAME", "hazmat"));
        properties.setPassword(env("HAZMAT_PG_PASSWORD", "hazmat-demo-password"));
        properties.setDimension(DIM);
        properties.setTable(TABLE);
        properties.setInitSchema(true);
        properties.setConnectTimeoutMs(2000);
        properties.setQueryTimeoutSeconds(3);
        return properties;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @BeforeEach
    void setUp() {
        store = new PgVectorStore(properties(
                env("HAZMAT_PG_URL", "jdbc:postgresql://localhost:5433/hazmat_vectors")));
        // 同一张测试表可能被上一次失败留下数据；每例从空表开始
        store.deleteByVersions(List.of("v1", "v2"));
        assertThat(store.available()).as("pgvector 应可用，请先启动容器").isTrue();
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.deleteByVersions(List.of("v1", "v2"));
            store.close();
        }
    }

    private static float[] vector(int hotIndex) {
        float[] vector = new float[DIM];
        vector[hotIndex] = 1f;
        return vector;
    }

    @Test
    void 建表后可以写入并按id对账() {
        store.upsert(List.of(
                new VectorRecord(101L, "67-56-1", "MSDS", "v1", "test-model", vector(0)),
                new VectorRecord(102L, "67-56-1", "GB", "v1", "test-model", vector(1))));

        assertThat(store.count(null)).isEqualTo(2);
        assertThat(store.count("v1")).isEqualTo(2);
        assertThat(store.count("v2")).isZero();
        assertThat(store.existingIds(List.of(101L, 102L, 999L)))
                .containsExactlyInAnyOrder(101L, 102L);
    }

    @Test
    void upsert_同_id_覆盖而不是重复插入() {
        store.upsert(List.of(new VectorRecord(201L, "67-56-1", "MSDS", "v1", "test-model", vector(0))));
        // 同一个 chunk 换版本重写：主键冲突走 UPDATE，行数不变
        store.upsert(List.of(new VectorRecord(201L, "67-56-1", "MSDS", "v2", "test-model", vector(1))));

        assertThat(store.count(null)).isEqualTo(1);
        assertThat(store.count("v1")).isZero();
        assertThat(store.count("v2")).isEqualTo(1);
    }

    @Test
    void 检索按余弦降序并过滤_casNo_docType_indexVersion() {
        store.upsert(List.of(
                new VectorRecord(301L, "67-56-1", "MSDS", "v1", "test-model", vector(0)),
                new VectorRecord(302L, "67-56-1", "GB", "v1", "test-model", vector(1)),
                new VectorRecord(303L, "50-00-0", "MSDS", "v1", "test-model", vector(0)),
                new VectorRecord(304L, "67-56-1", "MSDS", "v2", "test-model", vector(0))));

        // 查询向量与 301/303/304 完全相同，得分 1；302 正交，得分 0，被 minScore 丢掉
        List<ScoredChunk> all = store.search(vector(0), VectorFilter.NONE, 10, 0.5);
        assertThat(all).extracting(ScoredChunk::chunkId)
                .containsExactlyInAnyOrder(301L, 303L, 304L);
        assertThat(all).allSatisfy(hit -> assertThat(hit.score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6)));

        List<ScoredChunk> byCas = store.search(vector(0), VectorFilter.of("67-56-1", null), 10, 0.5);
        assertThat(byCas).extracting(ScoredChunk::chunkId).containsExactlyInAnyOrder(301L, 304L);

        List<ScoredChunk> byDocType = store.search(vector(0), VectorFilter.of(null, "MSDS"), 10, 0.5);
        assertThat(byDocType).extracting(ScoredChunk::chunkId).containsExactlyInAnyOrder(301L, 303L, 304L);

        List<ScoredChunk> byVersion = store.search(vector(0),
                new VectorFilter(null, null, "v1"), 10, 0.5);
        assertThat(byVersion).extracting(ScoredChunk::chunkId).containsExactlyInAnyOrder(301L, 303L);
    }

    @Test
    void 按版本删除只影响目标版本() {
        store.upsert(List.of(
                new VectorRecord(401L, "67-56-1", "MSDS", "v1", "test-model", vector(0)),
                new VectorRecord(402L, "67-56-1", "MSDS", "v2", "test-model", vector(1))));

        store.deleteByVersions(List.of("v1"));

        assertThat(store.count("v1")).isZero();
        assertThat(store.count("v2")).isEqualTo(1);
        assertThat(store.existingIds(List.of(401L, 402L))).containsExactly(402L);
    }

    @Test
    void 维度不符直接拒绝写入() {
        VectorRecord wrong = new VectorRecord(501L, "67-56-1", "MSDS", "v1", "test-model", new float[]{1f, 0f});

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.upsert(List.of(wrong)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("向量维度不符");
        assertThat(store.count(null)).isZero();
    }

    @Test
    void 连不上时可用性为假且检索返回空() {
        PgVectorProperties unreachable = properties("jdbc:postgresql://127.0.0.1:6543/nope");
        unreachable.setInitSchema(false);
        unreachable.setConnectTimeoutMs(300);
        try (PgVectorStore broken = new PgVectorStore(unreachable)) {
            assertThat(broken.available()).isFalse();
            assertThat(broken.search(vector(0), VectorFilter.NONE, 10, 0.0)).isEmpty();
            assertThat(broken.count(null)).isEqualTo(-1);
            assertThat(broken.existingIds(List.of(1L))).isEmpty();
        }
    }
}
