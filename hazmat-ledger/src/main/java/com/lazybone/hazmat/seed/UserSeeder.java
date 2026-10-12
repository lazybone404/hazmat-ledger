package com.lazybone.hazmat.seed;

import com.lazybone.hazmat.entity.AppUser;
import com.lazybone.hazmat.entity.UserRole;
import com.lazybone.hazmat.repository.AppUserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 演示账号。只在用户表为空时创建，不覆盖已有账号。
 *
 * 密码来自 {@code hazmat.security.seed-password}，启动日志不打印明文——
 * 日志经常被收集、转发，把密码写进去等于换了个地方存明文。
 *
 * 两个审批人账号是刻意的：双人复核要「两个不同的人」，只有一个演示账号时
 * 这条规则根本没法手工验证。
 *
 * 生产部署应关闭 {@code hazmat.security.seed-users}，或至少改掉默认密码。
 */
@Slf4j
@Component
public class UserSeeder implements ApplicationRunner {

    private record SeedUser(String username, String displayName, UserRole role) {
    }

    private static final List<SeedUser> SEED_USERS = List.of(
            new SeedUser("admin", "系统管理员", UserRole.ADMIN),
            new SeedUser("approver1", "审批员甲", UserRole.APPROVER),
            new SeedUser("approver2", "审批员乙", UserRole.APPROVER),
            new SeedUser("alice", "张三", UserRole.USER),
            new SeedUser("bob", "李四", UserRole.USER));

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${hazmat.security.seed-users:true}")
    private boolean enabled;

    @Value("${hazmat.security.seed-password:ChangeMe123!}")
    private String seedPassword;

    public UserSeeder(AppUserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        if (userRepository.count() > 0) {
            return;
        }

        List<String> created = SEED_USERS.stream()
                .map(seed -> {
                    userRepository.save(AppUser.builder()
                            .username(seed.username())
                            .displayName(seed.displayName())
                            .role(seed.role())
                            .passwordHash(passwordEncoder.encode(seedPassword))
                            .enabled(true)
                            .build());
                    return seed.username();
                })
                .toList();

        log.warn("已创建演示账号 {}，密码取自 hazmat.security.seed-password。"
                + "部署前请修改密码或关闭 hazmat.security.seed-users", created);
    }
}
