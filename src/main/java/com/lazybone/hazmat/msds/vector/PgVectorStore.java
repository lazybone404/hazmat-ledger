package com.lazybone.hazmat.msds.vector;

import com.lazybone.hazmat.entity.MsdsChunk;
import com.lazybone.hazmat.msds.ai.EmbeddingClient;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * pgvector 实现：向量存 PostgreSQL，检索下推给数据库，只回来块 id 与分数。
 *
 * <b>为什么只建第二个 DataSource、不建第二个 JPA EntityManagerFactory：</b>
 * MySQL 已经有主 JPA 与事务管理器；再加一套 EMF 会多一个事务管理器，
 * 而「同一事务里同时写两个库」本来也做不到（没有分布式事务）。
 * 这里的定位很清楚——pg 是可重建的派生数据，写入失败记下来对账，不回滚 MySQL。
 *
 * <b>为什么正文不搬过来：</b>正文的事实源是 MySQL。向量库只回答「哪几块最像」，
 * 服务层按 id 回表拿最新正文。这样修 MSDS 文字不需要重建向量库，
 * 也不会出现「向量库里的正文比台账里新」这种两个事实源的问题。
 *
 * <b>可用性：</b>构造时用 {@code initializationFailTimeout(-1)} 允许延迟建连，
 * pg 没起来应用照常启动；每次检索前做一次带 5 秒缓存的探活，失败就返回空结果，
 * 由服务层降级到字面路——这条路径与内存实现的可观测行为一致。
 */
public class PgVectorStore implements VectorStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStore.class);

    /** 表名进 SQL，只允许标识符字符，防止配置里写进注入片段 */
    private static final Pattern TABLE_NAME = Pattern.compile("^[a-z_][a-z0-9_]*$");

    /** 探活缓存的时长：比 30 秒的 token 缓存短，pg 恢复后能较快重新接上 */
    private static final Duration HEALTH_TTL = Duration.ofSeconds(5);

    /** existingIds 的 IN 列表分批大小，避免一次绑定过多参数 */
    private static final int ID_BATCH = 500;

    private final PgVectorProperties properties;
    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;
    private final String table;

    private volatile boolean healthy = false;
    private volatile Instant healthCheckedAt = Instant.EPOCH;

    /**
     * 索引被整体停用（例如四元组不符、旧向量作废）。
     * 与内存实现的「索引被清空」语义对齐：此时检索必须返回空，而不是去查旧数据。
     */
    private volatile boolean suspended = false;

    public PgVectorStore(PgVectorProperties properties) {
        this.properties = properties;
        this.table = properties.getTable();
        if (!TABLE_NAME.matcher(table).matches()) {
            throw new IllegalArgumentException("hazmat.vector.pg.table 只允许小写字母、数字与下划线: " + table);
        }
        if (properties.getDimension() <= 0) {
            throw new IllegalArgumentException("hazmat.vector.pg.dimension 必须大于 0");
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.getUrl());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setMaximumPoolSize(Math.max(1, properties.getPoolSize()));
        config.setMinimumIdle(1);
        config.setConnectionTimeout(properties.getConnectTimeoutMs());
        config.setPoolName("hazmat-pgvector");
        // 关键：pg 没起来时不阻断应用启动，交给 available() 降级
        config.setInitializationFailTimeout(-1);
        this.dataSource = new HikariDataSource(config);
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(Math.max(1, properties.getQueryTimeoutSeconds()));

        if (properties.isInitSchema()) {
            initSchema();
        }
    }

    @Override
    public String name() {
        return "pgvector";
    }

    @Override
    public boolean supportsIncrementalWrites() {
        return true;
    }

    /**
     * 刷新不重建数据：pg 里的行由编码流程写入，这里只更新「能不能用」的状态。
     *
     * <p>空列表表示索引应被停用（与内存实现清空索引对齐），检索直接返回空。
     */
    @Override
    public void refresh(List<MsdsChunk> chunks, EmbeddingClient client) {
        suspended = chunks == null || chunks.isEmpty();
        healthy = ping();
        healthCheckedAt = Instant.now();
        if (!healthy) {
            log.warn("pgvector 暂不可用，向量路将降级到字面检索: {}", properties.getUrl());
        }
    }

    @Override
    public List<ScoredChunk> search(float[] query, VectorFilter filter, int limit, double minScore) {
        if (!available() || query == null || query.length != properties.getDimension()) {
            return List.of();
        }
        VectorFilter effective = filter == null ? VectorFilter.NONE : filter;
        String literal = toLiteral(query);

        StringBuilder sql = new StringBuilder(
                "SELECT chunk_id, 1 - (embedding <=> CAST(? AS vector)) AS score FROM ")
                .append(table)
                .append(" WHERE 1 - (embedding <=> CAST(? AS vector)) >= ?");
        List<Object> args = new ArrayList<>(List.of(literal, literal, minScore));
        if (effective.casNo() != null) {
            sql.append(" AND cas_no = ?");
            args.add(effective.casNo());
        }
        if (effective.docType() != null) {
            sql.append(" AND doc_type = ?");
            args.add(effective.docType());
        }
        if (effective.indexVersion() != null) {
            sql.append(" AND index_version = ?");
            args.add(effective.indexVersion());
        }
        sql.append(" ORDER BY embedding <=> CAST(? AS vector) LIMIT ?");
        args.add(literal);
        args.add(Math.max(1, limit));

        return jdbc.query(sql.toString(),
                (rs, rowNum) -> new ScoredChunk(rs.getLong("chunk_id"), rs.getDouble("score")),
                args.toArray());
    }

    /**
     * 批量 upsert。维度不符直接抛错而不是跳过：跳过会让「向量库少了一批」变成静默问题，
     * 而原因（模型维度与向量库不一致）恰恰是必须让人看见的。
     */
    @Override
    public void upsert(List<VectorRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        int expected = properties.getDimension();
        List<Object[]> args = new ArrayList<>(records.size());
        for (VectorRecord record : records) {
            if (record.vector() == null || record.vector().length != expected) {
                throw new IllegalArgumentException("向量维度不符：配置 " + expected + "，实际 "
                        + (record.vector() == null ? 0 : record.vector().length)
                        + "。请换用与 pgvector 维度一致的 embedding 模型，"
                        + "或调整 hazmat.vector.pg.dimension 后重建索引");
            }
            args.add(new Object[]{
                    record.chunkId(), record.casNo(), record.docType(),
                    record.indexVersion(), record.modelId(), toLiteral(record.vector())
            });
        }
        String sql = "INSERT INTO " + table
                + " (chunk_id, cas_no, doc_type, index_version, model_id, embedding, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, CAST(? AS vector), now())"
                + " ON CONFLICT (chunk_id) DO UPDATE SET"
                + " cas_no = EXCLUDED.cas_no, doc_type = EXCLUDED.doc_type,"
                + " index_version = EXCLUDED.index_version, model_id = EXCLUDED.model_id,"
                + " embedding = EXCLUDED.embedding, updated_at = now()";
        jdbc.batchUpdate(sql, args);
    }

    @Override
    public void deleteByVersions(Collection<String> versions) {
        if (versions == null || versions.isEmpty()) {
            return;
        }
        List<String> values = versions.stream().filter(v -> v != null && !v.isBlank()).distinct().toList();
        if (values.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", values.stream().map(v -> "?").toList());
        jdbc.update("DELETE FROM " + table + " WHERE index_version IN (" + placeholders + ")",
                values.toArray());
    }

    @Override
    public long count(String indexVersion) {
        if (!available()) {
            return -1;
        }
        try {
            Long count = indexVersion == null || indexVersion.isBlank()
                    ? jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class)
                    : jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE index_version = ?",
                            Long.class, indexVersion);
            return count == null ? 0 : count;
        } catch (RuntimeException e) {
            markUnhealthy(e);
            return -1;
        }
    }

    @Override
    public Set<Long> existingIds(Collection<Long> chunkIds) {
        if (!available() || chunkIds == null || chunkIds.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = chunkIds.stream().filter(id -> id != null).distinct().toList();
        Set<Long> found = new HashSet<>();
        for (int start = 0; start < ids.size(); start += ID_BATCH) {
            List<Long> batch = ids.subList(start, Math.min(start + ID_BATCH, ids.size()));
            String placeholders = String.join(",", batch.stream().map(id -> "?").toList());
            found.addAll(jdbc.queryForList(
                    "SELECT chunk_id FROM " + table + " WHERE chunk_id IN (" + placeholders + ")",
                    Long.class, batch.toArray()));
        }
        return found;
    }

    @Override
    public boolean available() {
        if (suspended) {
            return false;
        }
        Instant now = Instant.now();
        if (now.isAfter(healthCheckedAt.plus(HEALTH_TTL))) {
            healthy = ping();
            healthCheckedAt = now;
        }
        return healthy;
    }

    @Override
    public void close() {
        dataSource.close();
    }

    /** 幂等建表。扩展 `vector` 需要超级用户，容器里的 init.sql 已建好；这里失败只警告 */
    private void initSchema() {
        try {
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        } catch (RuntimeException e) {
            log.warn("创建 vector 扩展失败（通常是没有权限，容器初始化脚本应已建好）: {}", e.getMessage());
        }
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + " chunk_id BIGINT PRIMARY KEY,"
                    + " cas_no VARCHAR(32),"
                    + " doc_type VARCHAR(16),"
                    + " index_version VARCHAR(64),"
                    + " model_id VARCHAR(64),"
                    + " embedding vector(" + properties.getDimension() + ") NOT NULL,"
                    + " updated_at TIMESTAMPTZ NOT NULL DEFAULT now())");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_" + table + "_hnsw ON " + table
                    + " USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_" + table + "_filter ON " + table
                    + " (index_version, doc_type, cas_no)");
            healthy = true;
            healthCheckedAt = Instant.now();
        } catch (RuntimeException e) {
            // 表建不起来时不要拖垮启动：与 pg 断连同一条路径——向量路降级
            markUnhealthy(e);
            log.warn("pgvector 建表失败，向量路将不可用: {}", e.getMessage());
        }
    }

    private boolean ping() {
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            return one != null && one == 1;
        } catch (RuntimeException e) {
            markUnhealthy(e);
            return false;
        }
    }

    private void markUnhealthy(RuntimeException e) {
        healthy = false;
        healthCheckedAt = Instant.now();
        log.warn("pgvector 探活/查询失败: {}", e.getMessage());
    }

    /** pgvector 接受 "[1.0,2.0]" 形式的文本，再 CAST 成 vector */
    private static String toLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            float value = vector[i];
            if (!Float.isFinite(value)) {
                // NaN/Inf 会被 pgvector 拒绝或污染距离计算，必须在进库前挡住
                throw new IllegalArgumentException("向量包含非有限值，拒绝写入 pgvector");
            }
            if (i > 0) {
                sb.append(',');
            }
            sb.append(value);
        }
        return sb.append(']').toString();
    }
}
