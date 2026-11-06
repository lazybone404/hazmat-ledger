package com.lazybone.hazmat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 本人修改密码。必须带旧密码：仅凭一个已登录的 token 就能改密，
 * 等于把「token 泄露」直接升级成「账号被永久接管」。
 */
public record ChangePasswordRequest(
        @NotBlank(message = "旧密码不能为空")
        String oldPassword,

        @NotBlank(message = "新密码不能为空")
        @Size(min = 8, max = 64, message = "新密码长度需在 8~64 个字符之间")
        String newPassword
) {
}
