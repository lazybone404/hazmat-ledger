package com.lazybone.hazmat.msds.vector;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * pgvector 装配：只有 {@code hazmat.vector.store=pgvector} 时才会加载。
 *
 * <p>注意这里**没有把 DataSource / JdbcTemplate 注册成 Bean**：
 * Spring Boot 的 JPA 自动配置会因为出现第二个 {@code DataSource} Bean 而歧义，
 * 轻则要写 {@code @Primary}，重则改变主库连接。连接池由 {@link PgVectorStore}
 * 自己持有并在关闭时释放，对外只暴露 {@link VectorStore} 一个角色。
 */
@Configuration
public class PgVectorStoreConfig {

    /**
     * {@code destroyMethod} 让容器在停机时关闭 Hikari 连接池；
     * 不注册成 DataSource Bean，是因为它只服务于向量存储，不该参与主事务。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "hazmat.vector.store", havingValue = "pgvector")
    public VectorStore pgVectorStore(PgVectorProperties properties) {
        return new PgVectorStore(properties);
    }
}
