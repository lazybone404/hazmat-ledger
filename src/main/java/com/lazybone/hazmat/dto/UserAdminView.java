package com.lazybone.hazmat.dto;

import com.lazybone.hazmat.entity.AppUser;

import java.time.LocalDateTime;

/** 管理端用户列表项。比 {@link UserView} 多了 id、启停状态与创建时间，仍不含密码哈希。 */
public record UserAdminView(
        Long id,
        String username,
        String displayName,
        String role,
        boolean enabled,
        LocalDateTime createdAt
) {

    public static UserAdminView from(AppUser user) {
        return new UserAdminView(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole().name(),
                user.isEnabled(),
                user.getCreatedAt());
    }
}
