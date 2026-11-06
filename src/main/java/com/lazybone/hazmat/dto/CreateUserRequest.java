package com.lazybone.hazmat.dto;

import com.lazybone.hazmat.entity.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 管理员建号。没有自助注册入口，账号只能由 ADMIN 创建。 */
public record CreateUserRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(max = 100, message = "用户名最长 100 个字符")
        String username,

        @NotBlank(message = "显示名不能为空")
        @Size(max = 100, message = "显示名最长 100 个字符")
        String displayName,

        @NotNull(message = "角色不能为空")
        UserRole role,

        @NotBlank(message = "初始密码不能为空")
        @Size(min = 8, max = 64, message = "初始密码长度需在 8~64 个字符之间")
        String password
) {
}
