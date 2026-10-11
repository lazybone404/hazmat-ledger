package com.lazybone.hazmat.dto;

import java.time.Instant;

/**
 * 登录结果。把过期时刻一起返回，客户端不必解析 JWT 就能决定何时刷新；
 * token 本身仍是唯一凭证，其余字段只是方便展示。
 */
public record LoginResponse(
        String token,
        String tokenType,
        long expiresInSeconds,
        Instant expiresAt,
        UserView user) {
}
