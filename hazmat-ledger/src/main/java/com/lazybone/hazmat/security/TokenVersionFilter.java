package com.lazybone.hazmat.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 在资源服务器完成 JWT 解码之后，再核对一次撤销版本。
 *
 * <p>放在 {@code BearerTokenAuthenticationFilter} 之后：解码得到 {@link Jwt} 是前提，
 * 放在它前面时 SecurityContext 还是空的，过滤器等于没写。
 *
 * <p>不通过时清空上下文并直接 401，不继续走后续过滤器——否则后面的接口
 * 仍可能从已认证的上下文里拿到身份。
 */
@Component
public class TokenVersionFilter extends OncePerRequestFilter {

    private final TokenVersionService tokenVersions;
    private final RestAuthenticationEntryPoint entryPoint;

    public TokenVersionFilter(TokenVersionService tokenVersions,
                              RestAuthenticationEntryPoint entryPoint) {
        this.tokenVersions = tokenVersions;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            // 升级前签发的 token 没有 tv，按 0 处理；同一时刻库里默认值也是 0，不会误伤
            Object claim = jwt.getClaim("tv");
            int tokenVersion = claim instanceof Number number ? number.intValue() : 0;
            Integer current = tokenVersions.currentVersion(jwt.getSubject());
            if (current == null || current != tokenVersion) {
                SecurityContextHolder.clearContext();
                entryPoint.commence(request, response,
                        new BadCredentialsException("token 已撤销，请重新登录"));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
