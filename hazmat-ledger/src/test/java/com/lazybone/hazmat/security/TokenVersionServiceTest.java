package com.lazybone.hazmat.security;

import com.lazybone.hazmat.entity.AppUser;
import com.lazybone.hazmat.repository.AppUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * token_version 缓存的 TTL 语义。
 *
 * <p>不用 {@code @SpringBootTest}：这里验证的是缓存判断本身，时钟和 TTL 都应该是
 * 测试说了算。起容器只会让用例更慢，还引入种子数据干扰。
 */
class TokenVersionServiceTest {

    private final AppUserRepository repository = mock(AppUserRepository.class);

    @Test
    @DisplayName("TTL 为 0 时每次回源，不缓存")
    void zeroTtlAlwaysHitsDatabase() {
        when(repository.findByUsername("alice")).thenReturn(Optional.of(user("alice", 3)));
        TokenVersionService service = new TokenVersionService(repository, Duration.ZERO);

        assertThat(service.currentVersion("alice")).isEqualTo(3);
        assertThat(service.currentVersion("alice")).isEqualTo(3);

        verify(repository, times(2)).findByUsername("alice");
    }

    @Test
    @DisplayName("TTL 内命中缓存，evict 后立即回源")
    void ttlCachesUntilEvicted() {
        when(repository.findByUsername("alice"))
                .thenReturn(Optional.of(user("alice", 1)))
                .thenReturn(Optional.of(user("alice", 2)));
        TokenVersionService service = new TokenVersionService(repository, Duration.ofMinutes(5));

        assertThat(service.currentVersion("alice")).isEqualTo(1);
        assertThat(service.currentVersion("alice")).isEqualTo(1);
        verify(repository, times(1)).findByUsername("alice");

        // 改密/停用后管理接口会 evict，同实例立即生效
        service.evict("alice");
        assertThat(service.currentVersion("alice")).isEqualTo(2);
        verify(repository, times(2)).findByUsername("alice");
    }

    @Test
    @DisplayName("用户不存在或已停用时返回 null，并把 null 也缓存住")
    void missingOrDisabledUserIsCachedAsNull() {
        when(repository.findByUsername("ghost")).thenReturn(Optional.empty());
        when(repository.findByUsername("bob")).thenReturn(Optional.of(disabledUser("bob")));
        TokenVersionService service = new TokenVersionService(repository, Duration.ofMinutes(5));

        assertThat(service.currentVersion("ghost")).isNull();
        assertThat(service.currentVersion("ghost")).isNull();
        assertThat(service.currentVersion(disabledUser("bob").getUsername())).isNull();
        // 伪造的 subject 不能绕过缓存反复打到库
        verify(repository, times(1)).findByUsername("ghost");
        verify(repository, times(1)).findByUsername("bob");
    }

    @Test
    @DisplayName("空用户名直接返回 null，不查库")
    void blankUsernameShortCircuits() {
        TokenVersionService service = new TokenVersionService(repository, Duration.ofMinutes(5));
        assertThat(service.currentVersion(" ")).isNull();
        assertThat(service.currentVersion(null)).isNull();
        verify(repository, times(0)).findByUsername(org.mockito.ArgumentMatchers.anyString());
    }

    private static AppUser user(String username, int tokenVersion) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setEnabled(true);
        user.setTokenVersion(tokenVersion);
        return user;
    }

    private static AppUser disabledUser(String username) {
        AppUser user = user(username, 1);
        user.setEnabled(false);
        return user;
    }
}
