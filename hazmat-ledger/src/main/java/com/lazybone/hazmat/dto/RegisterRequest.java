package com.lazybone.hazmat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 自助注册。默认关闭；打开后创建的是**待启用**账号，不能直接登录。
 *
 * <p>为什么不做成注册即可用：实验室危化品系统的账号本身就是一种权限，
 * 谁能领用、谁能审批必须由管理员确认。注册只负责收集申请，启用仍走用户管理。
 *
 * <p>用户名下限 3 是为了避免单字符账号在台账签名里难以辨认；上限与
 * {@code app_user.username} 列宽一致。
 */
public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 100, message = "用户名长度需在 3~100 个字符之间")
        String username,

        @NotBlank(message = "显示名不能为空")
        @Size(min = 1, max = 100, message = "显示名长度需在 1~100 个字符之间")
        String displayName,

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 64, message = "密码长度需在 8~64 个字符之间")
        String password) {
}
