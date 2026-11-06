package com.lazybone.hazmat.msds.vector;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * pgvector 存储的配置。只有 {@code hazmat.vector.store=pgvector} 时才会真正建连接。
 *
 * <p>口令不给默认值：连不上就降级到字面路，而不是拿一个空密码反复重试。
 *
 * <p>{@code table} 可配是为了测试：集成测试用独立的表，避免把开发环境里的
 * 真实向量清掉。运行时不要改它——表名会直接进 SQL。
 */
@Data
@Component
@ConfigurationProperties(prefix = "hazmat.vector.pg")
public class PgVectorProperties {

    private String url = "jdbc:postgresql://localhost:5433/hazmat_vectors";
    private String username = "hazmat";
    private String password = "";

    /** 连接池上限。向量写入是低频批处理，4 个连接足够；查询走主检索路径也是只读 */
    private int poolSize = 4;

    /** 向量维度。必须与 embedding 模型一致（bge-m3 为 1024） */
    private int dimension = 1024;

    /** 表名，入库前会校验只含小写字母、数字与下划线 */
    private String table = "msds_vector";

    /** 建连超时：pg 挂掉时不让检索线程等太久 */
    private int connectTimeoutMs = 3000;

    /** 单条查询超时 */
    private int queryTimeoutSeconds = 3;

    /** 启动时是否幂等建表、建索引。容器里的 init.sql 已经做过，这里是双保险 */
    private boolean initSchema = true;
}
