package com.lazybone.hazmat.dto;

import com.lazybone.hazmat.entity.UserRole;
import jakarta.validation.constraints.Size;

/**
 * 管理员修改账号。三项都可选，只改传了的字段。
 *
 * <p>不提供改用户名：用户名是台账里的签名，改掉会让历史记录对不上人。
 */
public record UpdateUserRequest(
        @Size(max = 100, message = "显示名最长 100 个字符")
        String displayName,

        UserRole role,

        Boolean enabled
) {
}
