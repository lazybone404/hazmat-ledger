package com.lazybone.hazmat.dto;

import com.lazybone.hazmat.entity.AppUser;

/** 对外可见的用户信息，绝不包含密码哈希。 */
public record UserView(String username, String displayName, String role) {

    public static UserView from(AppUser user) {
        return new UserView(user.getUsername(), user.getDisplayName(), user.getRole().name());
    }
}
