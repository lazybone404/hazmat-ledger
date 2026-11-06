package com.lazybone.hazmat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 管理员重置他人密码，不需要旧密码；重置后对方所有 token 立即失效。 */
public record ResetPasswordRequest(
        @NotBlank(message = "新密码不能为空")
        @Size(min = 8, max = 64, message = "新密码长度需在 8~64 个字符之间")
        String newPassword
) {
}
