package com.lazybone.hazmat.msds.vector;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量存储装配。默认（配置缺失）走内存实现，保证离线、无 Docker 的环境照常启动。
 *
 * <p>pgvector 实现在 B3 接入后由对应的 {@code @ConditionalOnProperty} 提供；
 * 两个实现互斥，切换只改 {@code hazmat.vector.store}。
 */
@Configuration
public class VectorStoreConfig {

    @Bean
    @ConditionalOnProperty(name = "hazmat.vector.store", havingValue = "memory", matchIfMissing = true)
    public VectorStore inMemoryVectorStore() {
        return new InMemoryVectorStore();
    }
}
