package com.lazybone.hazmat.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazybone.hazmat.entity.Chemical;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 试剂档案缓存。
 *
 * <b>只缓存档案，不缓存库存与台账。</b>这条线不是「缓存能放什么就放什么」的技术判断，
 * 而是业务判断：档案（CAS 号、名称、危险等级、开封后可用天数）陈旧 30 秒没有后果，
 * 库存余额和流水陈旧 30 秒就是**错账**——而这个系统的全部价值就是账对得上。
 * 所以 {@code hazmat:chemical:{id}} 之外不再有第二个 key，扣减、状态变更、台账写入
 * 一律直接打到数据库。
 *
 * <b>为什么档案可以缓存而不用考虑「写后读一致性」：</b>档案只增不改——
 * {@code ChemicalController} 没有 PUT / DELETE，建档之后 CAS 号、危险等级都不会变。
 * 于是不存在「缓存里的旧值覆盖新值」的时间窗：新建的 id 是自增的，
 * 在它存在之前不可能有人按这个 id 查过（空值哨兵也就永远不会挡住一次真实建档）。
 * 这个前提一旦被打破——比如以后开放档案编辑——{@link com.lazybone.hazmat.service.ChemicalService}
 * 的写路径必须补一次删除缓存，否则改完化验等级要等 30 分钟才生效。
 *
 * <b>Redis 不可用时退化为直接查库。</b>所有异常在这里被吃掉并返回 {@link State#UNAVAILABLE}，
 * 调用方据此回源，接口不报错、不抛 500。代价是 Redis 挂掉时每一次读都白等一个超时，
 * 所以 {@code spring.data.redis.timeout} 设为 1 秒而不是默认值。
 *
 * <b>不做击穿互斥，是有意的。</b>击穿的前提是「热点 key 过期瞬间大量并发回源」。
 * 这里回源是一条主键点查，且 TTL 带随机抖动，同一 key 同时过期的概率极低；
 * 而加 SETNX 互斥意味着每次未命中都要多两次 Redis 往返、多一个「等待重试」分支，
 * 换来的只是把几条并发主键查询合并成一条。不值。
 * 如果以后改成缓存列表页（一次回源是几十毫秒的聚合查询），那就值得加了。
 *
 * <b>穿透由空值哨兵挡住。</b>查一个不存在的 id 时写一个空串（TTL 1 分钟），
 * 后续同样的请求直接得到「不存在」，不再穿透到数据库。
 */
@Slf4j
@Component
public class ChemicalCache {

    /** 命中状态。调用方必须区分「确实不存在」与「没查到」，否则空值哨兵会被当成缺数据 */
    public enum State {
        /** 命中，且带回了档案 */
        HIT,
        /** 命中空值哨兵：缓存确认这个 id 不存在 */
        MISSING,
        /** 未命中，需要回源并回写 */
        MISS,
        /** 缓存不可用（未开启或 Redis 出错），直接回源，不回写 */
        UNAVAILABLE
    }

    public record Lookup(State state, Chemical chemical) {

        static Lookup hit(Chemical chemical) {
            return new Lookup(State.HIT, chemical);
        }

        static Lookup of(State state) {
            return new Lookup(state, null);
        }

        /** 是否要去数据库取 */
        public boolean needsLoad() {
            return state == State.MISS || state == State.UNAVAILABLE;
        }

        /** 回源之后是否应该回写：只有「确实没命中」才写，缓存不可用时写了也没意义 */
        public boolean shouldFill() {
            return state == State.MISS;
        }
    }

    private static final String KEY_PREFIX = "hazmat:chemical:";

    /** 空值哨兵的存活时间：短到坏 id 不会长期占着缓存，长到足以挡住连续的穿透请求 */
    private static final Duration MISSING_TTL = Duration.ofMinutes(1);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ChemicalCacheProperties properties;

    public ChemicalCache(StringRedisTemplate redis,
                         ObjectMapper objectMapper,
                         ChemicalCacheProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Lookup lookup(Long id) {
        if (id == null) {
            return Lookup.of(State.MISSING);
        }
        if (!properties.isEnabled()) {
            return Lookup.of(State.UNAVAILABLE);
        }

        String raw;
        try {
            raw = redis.opsForValue().get(key(id));
        } catch (Exception e) {
            // 只记 debug：Redis 不可用可能持续很久，warn 会把日志刷满，
            // 而这件事本身不影响任何接口的正确性
            log.debug("读取档案缓存失败，回源数据库 id={}：{}", id, e.toString());
            return Lookup.of(State.UNAVAILABLE);
        }

        if (raw == null) {
            return Lookup.of(State.MISS);
        }
        if (raw.isEmpty()) {
            return Lookup.of(State.MISSING);
        }

        try {
            return Lookup.hit(objectMapper.readValue(raw, Snapshot.class).toChemical());
        } catch (Exception e) {
            // 缓存里的内容解析不出来（格式变了、被手工改过）：当作未命中并回写覆盖掉
            log.debug("档案缓存内容无法解析，按未命中处理 id={}：{}", id, e.toString());
            return Lookup.of(State.MISS);
        }
    }

    public void put(Chemical chemical) {
        if (!properties.isEnabled() || chemical == null || chemical.getId() == null) {
            return;
        }
        try {
            redis.opsForValue().set(key(chemical.getId()),
                    objectMapper.writeValueAsString(Snapshot.of(chemical)),
                    ttlWithJitter());
        } catch (Exception e) {
            // 写缓存失败不影响任何结果：下一次读只是再查一次库
            log.debug("写入档案缓存失败 id={}：{}", chemical.getId(), e.toString());
        }
    }

    public void putMissing(Long id) {
        if (!properties.isEnabled() || id == null) {
            return;
        }
        try {
            redis.opsForValue().set(key(id), "", MISSING_TTL);
        } catch (Exception e) {
            log.debug("写入空值哨兵失败 id={}：{}", id, e.toString());
        }
    }

    private String key(Long id) {
        return KEY_PREFIX + id;
    }

    private Duration ttlWithJitter() {
        Duration base = properties.getTtl();
        Duration jitter = properties.getJitter();
        if (jitter == null || jitter.isZero() || jitter.isNegative()) {
            return base;
        }
        return base.plusSeconds(ThreadLocalRandom.current().nextLong(jitter.getSeconds()));
    }

    /**
     * 缓存里存的结构，和实体解耦。
     *
     * <b>为什么不直接序列化 {@link Chemical}：</b>实体是跟着表结构走的，
     * 加一个字段就会被自动写进缓存；而缓存里的旧 JSON 在新代码下反序列化时，
     * 新字段是 null——表现为「缓存命中的档案比数据库少一列」，
     * 而且只在那批旧 key 存活期间出现。用一个显式的快照结构，
     * 加字段时开发者必须主动决定它要不要进缓存，这个决定不会被静默跳过。
     */
    record Snapshot(Long id,
                    String casNo,
                    String name,
                    String category,
                    String hazardLevel,
                    String unit,
                    String spec,
                    String msdsUrl,
                    Integer postOpenDays,
                    LocalDateTime createdAt) {

        static Snapshot of(Chemical c) {
            return new Snapshot(c.getId(), c.getCasNo(), c.getName(), c.getCategory(),
                    c.getHazardLevel(), c.getUnit(), c.getSpec(), c.getMsdsUrl(),
                    c.getPostOpenDays(), c.getCreatedAt());
        }

        Chemical toChemical() {
            return Chemical.builder()
                    .id(id)
                    .casNo(casNo)
                    .name(name)
                    .category(category)
                    .hazardLevel(hazardLevel)
                    .unit(unit)
                    .spec(spec)
                    .msdsUrl(msdsUrl)
                    .postOpenDays(postOpenDays)
                    .createdAt(createdAt)
                    .build();
        }
    }
}
