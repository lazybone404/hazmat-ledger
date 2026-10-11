package com.lazybone.hazmat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 登录。密码上限只是防止把超长串丢给 BCrypt，不是密码策略。 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空") @Size(max = 100) String username,
        @NotBlank(message = "密码不能为空") @Size(max = 200) String password) {
}
