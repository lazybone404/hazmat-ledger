package com.lazybone.hazmat.security;

import com.lazybone.hazmat.entity.AppUser;
import com.lazybone.hazmat.repository.AppUserRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 校验 JWT 的撤销版本，带 30 秒本地缓存。
 *
 * <p>为什么需要：JWT 是无状态的，签发后服务端改不了。停用账号、改密、改角色时
 * 把 {@code app_user.token_version} 加一，旧 token 里的 {@code tv} 对不上即失效。
 *
 * <p>为什么不每请求查库：登录后的每个接口都要过这道校验，一次索引查询虽快，
 * 在检索/报表这类高频路径上仍是固定开销。30 秒缓存把成本摊到几乎为零，
 * 代价是「停用/改角色」最多滞后 30 秒生效——这在本项目是可接受的，
 * 而且管理接口在改完后会主动 {@link #evict(String)}，同实例立即生效。
 *
 * <p>缓存里也存「用户不存在/已停用」的结果：否则伪造的 subject 会绕过缓存、
 * 每次请求都打到库里。键数量由用户数封顶，不需要额外淘汰。
 */
@Component
public class TokenVersionService {

    private static final Duration TTL = Duration.ofSeconds(30);

    private record Cached(Integer version, Instant expiresAt) {
    }

    private final AppUserRepository userRepository;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public TokenVersionService(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * 当前有效版本；用户不存在或已停用返回 {@code null}。
     * 调用方据此判 401，而不是区分「不存在」与「停用」——两者对外都是过期。
     */
    public Integer currentVersion(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        Instant now = Instant.now();
        Cached cached = cache.get(username);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.version();
        }
        Integer version = userRepository.findByUsername(username)
                .filter(AppUser::isEnabled)
                .map(AppUser::getTokenVersion)
                .orElse(null);
        cache.put(username, new Cached(version, now.plus(TTL)));
        return version;
    }

    /** 账号状态变更后调用；不 evict 的话本实例最多还要用 30 秒旧版本 */
    public void evict(String username) {
        if (username != null) {
            cache.remove(username);
        }
    }
}
