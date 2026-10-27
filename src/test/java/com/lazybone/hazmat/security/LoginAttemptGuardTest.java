package com.lazybone.hazmat.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录失败计数的时间语义。
 *
 * 用毫秒级的锁定时长而不是配置里的 1 分钟：解锁依赖的是时间流逝，
 * 测试只需要验证「到期后恢复」，不需要真的等一分钟。
 * 这也是不给生产代码加 {@code reset()} 的原因——真实的时间路径被覆盖到了。
 */
class LoginAttemptGuardTest {

    private static final Duration LOCK = Duration.ofMillis(150);

    @Test
    @DisplayName("达到阈值才锁定，锁定期满自动解锁")
    void locksAtThresholdAndUnlocksAfterDuration() throws InterruptedException {
        LoginAttemptGuard guard = new LoginAttemptGuard(3, LOCK);

        guard.onFailure("u");
        guard.onFailure("u");
        assertThat(guard.isLocked("u")).as("两次失败还没到阈值").isFalse();

        guard.onFailure("u");
        assertThat(guard.isLocked("u")).isTrue();

        Thread.sleep(250);
        assertThat(guard.isLocked("u")).as("锁定期满后应自动解锁").isFalse();
    }

    @Test
    @DisplayName("登录成功清零：之前的失败不会累积到下一次")
    void successClearsFailures() {
        LoginAttemptGuard guard = new LoginAttemptGuard(3, LOCK);

        guard.onFailure("u");
        guard.onFailure("u");
        guard.onSuccess("u");

        guard.onFailure("u");
        guard.onFailure("u");
        assertThat(guard.isLocked("u")).isFalse();
    }

    @Test
    @DisplayName("失败间隔超过锁定窗口时重新计数，不会因为零星的输错被锁")
    void failuresOutsideWindowStartOver() throws InterruptedException {
        LoginAttemptGuard guard = new LoginAttemptGuard(3, LOCK);

        guard.onFailure("u");
        guard.onFailure("u");
        Thread.sleep(250);

        guard.onFailure("u");
        assertThat(guard.isLocked("u"))
                .as("前两次已超出窗口，本次只算第 1 次")
                .isFalse();
    }

    @Test
    @DisplayName("计数按用户名隔离")
    void countersAreIsolatedPerUser() {
        LoginAttemptGuard guard = new LoginAttemptGuard(2, LOCK);

        guard.onFailure("a");
        guard.onFailure("a");

        assertThat(guard.isLocked("a")).isTrue();
        assertThat(guard.isLocked("b")).isFalse();
    }
}
