package com.lazybone.hazmat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lazybone.hazmat.cache.ChemicalCache;
import com.lazybone.hazmat.cache.ChemicalCacheProperties;
import com.lazybone.hazmat.entity.Chemical;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 档案缓存的四种状态，以及每种状态下调用方该不该回源。
 *
 * 直接测 {@link ChemicalCache} 而不是起 Spring 上下文连真 Redis：
 * 这里验证的是本类的分支逻辑（命中 / 未命中 / 空值哨兵 / 不可用），与 Redis 实现无关。
 * 真实连通性由 {@code ChemicalCacheFallbackTest}（把端口指向空端口）覆盖。
 */
class ChemicalCacheTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private ChemicalCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        ChemicalCacheProperties properties = new ChemicalCacheProperties();
        properties.setEnabled(true);
        properties.setTtl(Duration.ofMinutes(30));
        properties.setJitter(Duration.ofMinutes(5));

        cache = new ChemicalCache(redis,
                new ObjectMapper().registerModule(new JavaTimeModule()), properties);
    }

    private Chemical sample() {
        return Chemical.builder()
                .id(7L)
                .casNo("67-56-1")
                .name("甲醇")
                .category("易燃")
                .hazardLevel("高")
                .unit("mL")
                .spec("AR 500mL")
                .msdsUrl("/msds/67-56-1.md")
                .postOpenDays(90)
                .createdAt(LocalDateTime.of(2026, 1, 1, 8, 0))
                .build();
    }

    @Test
    @DisplayName("写入再读出：字段完整往返，状态为 HIT")
    void roundTripRestoresAllFields() {
        // 走真实写入路径拿到序列化结果，不手写 JSON——
        // 快照结构改了，这个测试会立刻红，而不是在线上表现为「缓存命中的档案少一列」
        cache.put(sample());
        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        verify(ops).set(eq("hazmat:chemical:7"), written.capture(), any(Duration.class));

        when(ops.get("hazmat:chemical:7")).thenReturn(written.getValue());

        ChemicalCache.Lookup lookup = cache.lookup(7L);

        assertEquals(ChemicalCache.State.HIT, lookup.state());
        Chemical restored = lookup.chemical();
        assertEquals(7L, restored.getId());
        assertEquals("67-56-1", restored.getCasNo());
        assertEquals("甲醇", restored.getName());
        assertEquals("高", restored.getHazardLevel());
        assertEquals(90, restored.getPostOpenDays());
        assertEquals(LocalDateTime.of(2026, 1, 1, 8, 0), restored.getCreatedAt());
        assertTrue(restored.isHighHazard());
        assertFalse(lookup.needsLoad(), "命中之后不该再查库");
    }

    @Test
    @DisplayName("key 不存在：返回 MISS，调用方回源并回写")
    void absentKeyIsMiss() {
        when(ops.get(anyString())).thenReturn(null);

        ChemicalCache.Lookup lookup = cache.lookup(9L);

        assertEquals(ChemicalCache.State.MISS, lookup.state());
        assertTrue(lookup.needsLoad());
        assertTrue(lookup.shouldFill());
        assertNull(lookup.chemical());
    }

    @Test
    @DisplayName("空值哨兵：返回 MISSING，不再穿透数据库")
    void sentinelIsMissing() {
        when(ops.get("hazmat:chemical:404")).thenReturn("");

        ChemicalCache.Lookup lookup = cache.lookup(404L);

        assertEquals(ChemicalCache.State.MISSING, lookup.state());
        assertFalse(lookup.needsLoad(), "哨兵命中不应该再去查库");
    }

    @Test
    @DisplayName("Redis 不可用：返回 UNAVAILABLE，回源但不回写")
    void unavailableDegrades() {
        when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("connection refused"));

        ChemicalCache.Lookup lookup = cache.lookup(1L);

        assertEquals(ChemicalCache.State.UNAVAILABLE, lookup.state());
        assertTrue(lookup.needsLoad(), "缓存挂了必须回源，接口不能因此失败");
        assertFalse(lookup.shouldFill(), "回源后写缓存也没意义，不必再等一次超时");
    }

    @Test
    @DisplayName("缓存内容解析不出来：按未命中处理，回源并覆盖坏值")
    void brokenContentIsTreatedAsMiss() {
        when(ops.get(anyString())).thenReturn("{not-json");

        ChemicalCache.Lookup lookup = cache.lookup(3L);

        assertEquals(ChemicalCache.State.MISS, lookup.state());
        assertTrue(lookup.shouldFill(), "坏值必须被下一次写入覆盖，否则永远命中失败");
    }

    @Test
    @DisplayName("开关关闭：完全不碰 Redis")
    void disabledNeverTouchesRedis() {
        ChemicalCacheProperties off = new ChemicalCacheProperties();
        off.setEnabled(false);
        ChemicalCache disabled = new ChemicalCache(redis, new ObjectMapper(), off);

        ChemicalCache.Lookup lookup = disabled.lookup(1L);

        assertEquals(ChemicalCache.State.UNAVAILABLE, lookup.state());
        verify(redis, never()).opsForValue();
    }

    @Test
    @DisplayName("写入失败被吞掉：不影响调用方")
    void writeFailureIsSwallowed() {
        doThrow(new RedisConnectionFailureException("boom"))
                .when(ops).set(anyString(), anyString(), any(Duration.class));

        // 不抛异常即通过：缓存写失败最多让下一次读多查一次库
        cache.put(sample());
        cache.putMissing(123L);
    }

    @Test
    @DisplayName("空值哨兵的 TTL 是分钟级，坏 id 不会长期占位")
    void sentinelUsesShortTtl() {
        cache.putMissing(123L);

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(ops).set(eq("hazmat:chemical:123"), eq(""), ttl.capture());
        assertTrue(ttl.getValue().toMinutes() <= 2);
    }

    @Test
    @DisplayName("正常写入的 TTL 落在 [ttl, ttl + jitter) 内，用于抵消同时过期")
    void normalTtlCarriesJitter() {
        cache.put(sample());

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(ops).set(eq("hazmat:chemical:7"), anyString(), ttl.capture());
        assertTrue(ttl.getValue().compareTo(Duration.ofMinutes(30)) >= 0);
        assertTrue(ttl.getValue().compareTo(Duration.ofMinutes(35)) < 0);
    }
}
