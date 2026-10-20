package com.lazybone.hazmat.controller;

import com.lazybone.hazmat.common.ApiResponse;
import com.lazybone.hazmat.common.UnauthorizedException;
import com.lazybone.hazmat.dto.LoginRequest;
import com.lazybone.hazmat.dto.LoginResponse;
import com.lazybone.hazmat.dto.UserView;
import com.lazybone.hazmat.entity.AppUser;
import com.lazybone.hazmat.repository.AppUserRepository;
import com.lazybone.hazmat.security.JwtService;
import com.lazybone.hazmat.security.LoginAttemptGuard;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录与当前用户。
 *
 * 没做注册接口：账号由管理员建档（或种子数据），实验室系统里自助注册没有业务意义，
 * 反而多一个需要防滥用的入口。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * 用户不存在时也拿它跑一次 BCrypt，避免「响应特别快 = 用户名不存在」这种枚举信号。
     * 值本身不重要，只要是一个格式合法的 BCrypt 哈希。
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptGuard loginAttemptGuard;

    public AuthController(AppUserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          JwtService jwtService,
                          LoginAttemptGuard loginAttemptGuard) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginAttemptGuard = loginAttemptGuard;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        // 锁定期内连 BCrypt 都不跑：登录接口是公开的，而 BCrypt 每次要几十毫秒 CPU，
        // 不挡住的话几十个并发请求就能把核对满。这里省下的正是被攻击时最紧张的资源。
        if (loginAttemptGuard.isLocked(request.username())) {
            throw new UnauthorizedException("尝试次数过多，请稍后再试");
        }

        AppUser user = userRepository.findByUsername(request.username()).orElse(null);
        boolean passwordMatches = passwordEncoder.matches(
                request.password(),
                user == null ? DUMMY_HASH : user.getPasswordHash());

        // 用户不存在、密码错误、账号停用返回同一句话：不告诉调用方是哪一种
        if (user == null || !user.isEnabled() || !passwordMatches) {
            loginAttemptGuard.onFailure(request.username());
            throw new UnauthorizedException("用户名或密码错误");
        }

        loginAttemptGuard.onSuccess(request.username());
        JwtService.IssuedToken issued = jwtService.issue(user);
        return ApiResponse.ok(new LoginResponse(
                issued.token(), "Bearer", issued.expiresInSeconds(), issued.expiresAt(),
                UserView.from(user)));
    }

    @GetMapping("/me")
    public ApiResponse<UserView> me(@AuthenticationPrincipal Jwt jwt) {
        AppUser user = userRepository.findByUsername(jwt.getSubject())
                .orElseThrow(() -> new UnauthorizedException("账号不存在"));
        if (!user.isEnabled()) {
            throw new UnauthorizedException("账号已停用");
        }
        return ApiResponse.ok(UserView.from(user));
    }
}
