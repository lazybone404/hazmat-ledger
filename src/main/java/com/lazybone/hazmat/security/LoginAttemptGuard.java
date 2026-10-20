package com.lazybone.hazmat.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败计数与短时锁定。
 *
 * <b>为什么需要它：</b>登录接口是公开的，而 BCrypt 是故意设计成很慢的
 * （默认强度下每次校验约 50–100ms CPU）。这本来是防离线爆破的优点，
 * 但在线爆破时变成了放大器——每秒几十个请求就能吃掉一个核，
 * 而且这些请求不需要任何凭证。限流保护的是**服务端 CPU**，不只是账号。
 *
 * <b>为什么不做 IP 维度：</b>实验室出口通常是同一个 NAT 地址，
 * 按 IP 限流会把整个实验室一起锁掉；按用户名计数误伤范围最小。
 * 代价是攻击者知道某个用户名时可以故意把它锁 1 分钟——内部系统接受这个代价，
 * 确实需要更强的防护应该上验证码或统一的认证网关，而不是在业务服务里堆规则。
 *
 * <b>为什么用内存 Map：</b>多副本部署时各算各的，锁定不跨实例；重启即清零。
 * 要做准确的分布式计数必须引入 Redis，而本项目刻意没有 Redis（见 README）。
 * 这里的定位是「防脚本误刷与低级爆破」，不是「抗分布式撞库」。
 */
@Component
public class LoginAttemptGuard {

    /**
     * 记录数上限。key 是用户输入的用户名，攻击者可以随便编，
     * 不设上限就是一个内存增长漏洞。超过上限时先清过期记录，
     * 仍然超限则整体清空——计数丢失的代价远小于 OOM。
     */
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final int maxFailures;
    private final Duration lockDuration;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    public LoginAttemptGuard(
            @Value("${hazmat.security.login-max-failures:5}") int maxFailures,
            @Value("${hazmat.security.login-lock:1m}") Duration lockDuration) {
        this.maxFailures = maxFailures;
        this.lockDuration = lockDuration;
    }

    /**
     * 当前是否处于锁定期。
     *
     * 锁定期内即使密码正确也拒绝：否则爆破者只要密码碰对就能进，
     * 而「密码对不对」本身就是要保护的信息。
     */
    public boolean isLocked(String username) {
        Attempt attempt = attempts.get(username);
        if (attempt == null) {
            return false;
        }
        Instant now = Instant.now();
        if (attempt.lockedUntil() != null) {
            if (now.isBefore(attempt.lockedUntil())) {
                return true;
            }
            attempts.remove(username, attempt);
            return false;
        }
        // 没到锁定次数，但已经很久没再失败：当作一次新的尝试序列
        if (attempt.lastFailureAt().plus(lockDuration).isBefore(now)) {
            attempts.remove(username, attempt);
        }
        return false;
    }

    /** 记一次失败；达到阈值时开始锁定 */
    public void onFailure(String username) {
        if (attempts.size() >= MAX_TRACKED_KEYS) {
            evict();
        }
        Instant now = Instant.now();
        attempts.compute(username, (key, previous) -> {
            boolean expired = previous != null
                    && previous.lockedUntil() == null
                    && previous.lastFailureAt().plus(lockDuration).isBefore(now);
            int failures = (previous == null || expired ? 0 : previous.failures()) + 1;
            Instant lockedUntil = (failures >= maxFailures || (previous != null && previous.lockedUntil() != null))
                    ? now.plus(lockDuration)
                    : null;
            return new Attempt(failures, now, lockedUntil);
        });
    }

    /** 登录成功立即清零：成功本身已经证明是正常用户 */
    public void onSuccess(String username) {
        attempts.remove(username);
    }

    private void evict() {
        Instant now = Instant.now();
        attempts.entrySet().removeIf(e -> {
            Attempt a = e.getValue();
            Instant deadline = a.lockedUntil() != null ? a.lockedUntil() : a.lastFailureAt().plus(lockDuration);
            return now.isAfter(deadline);
        });
        if (attempts.size() >= MAX_TRACKED_KEYS) {
            attempts.clear();
        }
    }

    private record Attempt(int failures, Instant lastFailureAt, Instant lockedUntil) {
    }
}
