package com.lazybone.hazmat.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 试剂档案缓存的配置。前缀 {@code hazmat.cache.chemical}。
 *
 * <b>为什么缓存可以做成开关：</b>它的收益来自「读多写少」，而不是「读不动了」。
 * 关掉之后接口行为完全不变，只是每次都查库——这正好说明缓存不是业务依赖，
 * 而是一层随时可以摘掉的加速。surefire 里统一关掉它，测试就不必启动 Redis。
 */
@Data
@Component
@ConfigurationProperties(prefix = "hazmat.cache.chemical")
public class ChemicalCacheProperties {

    /** 总开关。false 时所有读取都走数据库，写缓存也不发生 */
    private boolean enabled = true;

    /**
     * 基础存活时间。
     *
     * 30 分钟的依据不是「数据多久变一次」（档案只增不改，理论上可以永久缓存），
     * 而是**给自己留一条不需要人工干预的自愈路径**：缓存格式变了、脏数据进来了，
     * 最迟 30 分钟自动消失，不用等到有人去清 key。
     */
    private Duration ttl = Duration.ofMinutes(30);

    /**
     * TTL 随机抖动上限，实际存活时间落在 [ttl, ttl + jitter) 内。
     *
     * 同一时刻批量写入的 key（例如一次批量导入档案）如果没有抖动，会在同一秒集体过期，
     * 那一刻所有请求同时回源。加抖动是抵消这种「同时过期」的最小成本手段。
     */
    private Duration jitter = Duration.ofMinutes(5);
}
