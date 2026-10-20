package com.lazybone.hazmat.security;

import com.lazybone.hazmat.entity.AppUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 签发 JWT。
 *
 * 只放三类信息：是谁（sub）、能做什么（roles）、什么时候过期（exp）。
 * 不放显示名之外的业务数据——token 是客户端可见的，放得越多，越容易被当成可信输入。
 * 需要用户详情时用 {@code /api/auth/me} 查库。
 *
 * 用对称密钥（HS256）而不是 RSA：这是单服务签发、单服务校验的场景，
 * 非对称的好处（第三方只需公钥）用不上，反而多一套密钥管理。
 */
@Service
public class JwtService {

    private static final String ISSUER = "hazmat-ledger";

    private final JwtEncoder encoder;
    private final Duration ttl;

    public JwtService(JwtEncoder encoder,
                      @Value("${hazmat.security.token-ttl}") Duration ttl) {
        this.encoder = encoder;
        this.ttl = ttl;
    }

    public IssuedToken issue(AppUser user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(user.getUsername())
                .claim("roles", List.of(user.getRole().name()))
                .claim("displayName", user.getDisplayName())
                .build();

        String token = encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new IssuedToken(token, expiresAt, ttl.toSeconds());
    }

    public record IssuedToken(String token, Instant expiresAt, long expiresInSeconds) {
    }
}
