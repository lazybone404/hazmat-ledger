package com.lazybone.hazmat.controller;

import com.lazybone.hazmat.common.ApiResponse;
import com.lazybone.hazmat.common.BusinessException;
import com.lazybone.hazmat.common.ForbiddenException;
import com.lazybone.hazmat.common.UnauthorizedException;
import com.lazybone.hazmat.dto.ChangePasswordRequest;
import com.lazybone.hazmat.dto.LoginRequest;
import com.lazybone.hazmat.dto.LoginResponse;
import com.lazybone.hazmat.dto.RegisterRequest;
import com.lazybone.hazmat.dto.UserView;
import com.lazybone.hazmat.entity.AppUser;
import com.lazybone.hazmat.entity.UserRole;
import com.lazybone.hazmat.repository.AppUserRepository;
import com.lazybone.hazmat.security.JwtService;
import com.lazybone.hazmat.security.LoginAttemptGuard;
import com.lazybone.hazmat.security.TokenVersionService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录、当前用户与自助注册。
 *
 * <p>账号默认由管理员建档（或种子数据）。自助注册是**可选的**入口，
 * 由 {@code hazmat.security.registration.enabled} 控制，默认关闭；
 * 打开后注册出来的也只是待启用账号，仍需管理员在用户管理里启用。
 * 这样既保留自助申请的便利，又不把「能登录」这件事交给匿名调用方。
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
    private final TokenVersionService tokenVersions;
    private final boolean registrationEnabled;

    public AuthController(AppUserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          JwtService jwtService,
                          LoginAttemptGuard loginAttemptGuard,
                          TokenVersionService tokenVersions,
                          @Value("${hazmat.security.registration.enabled:false}")
                          boolean registrationEnabled) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginAttemptGuard = loginAttemptGuard;
        this.tokenVersions = tokenVersions;
        this.registrationEnabled = registrationEnabled;
    }

    /**
     * 自助注册。默认关闭；打开后创建的是 {@code enabled=false} 的 USER 账号。
     *
     * <p><b>用户名已存在时也返回同样的成功。</b>这个接口是公开的，如果「已存在」
     * 和「注册成功」返回不同结果，它就变成了一个批量探测账号是否存在的工具。
     * 真正的注册人不会因为没收到区分信息而受损——管理员启用时会看到账号。
     *
     * <p>无论是否写入，都先跑一次 BCrypt：否则「响应快 = 用户名已存在」仍然可被观察。
     */
    @PostMapping("/register")
    public ApiResponse<Void> register(@Valid @RequestBody RegisterRequest request) {
        if (!registrationEnabled) {
            throw new ForbiddenException("未开放自助注册，请联系管理员建档");
        }
        String hash = passwordEncoder.encode(request.password());
        if (userRepository.findByUsername(request.username()).isEmpty()) {
            try {
                userRepository.save(AppUser.builder()
                        .username(request.username())
                        .displayName(request.displayName())
                        .passwordHash(hash)
                        .role(UserRole.USER)
                        .enabled(false)
                        .build());
            } catch (DataIntegrityViolationException e) {
                // 并发注册同一个用户名：另一个请求刚插进去。对外仍是成功，
                // 账号是否存在不在这条接口的告知范围内。
            }
        }
        return ApiResponse.ok(null);
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

    /**
     * 本人改密。改完自增撤销版本，**当前 token 也一并失效**——
     * 如果保留当前会话，攻击者拿到旧 token 后只要不刷新就仍然可用，改密就白做了。
     * 前端需要重新登录，这个代价是刻意的。
     */
    @PostMapping("/password")
    public ApiResponse<Void> changePassword(@AuthenticationPrincipal Jwt jwt,
                                            @Valid @RequestBody ChangePasswordRequest request) {
        AppUser user = userRepository.findByUsername(jwt.getSubject())
                .orElseThrow(() -> new UnauthorizedException("账号不存在"));
        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw new BusinessException("旧密码不正确");
        }
        if (request.oldPassword().equals(request.newPassword())) {
            throw new BusinessException("新密码不能与旧密码相同");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);
        tokenVersions.evict(user.getUsername());
        return ApiResponse.ok(null);
    }

    /** 让本账号在所有设备上的 token 立即失效；本质上就是撤销版本 +1。 */
    @PostMapping("/logout-all")
    public ApiResponse<Void> logoutAll(@AuthenticationPrincipal Jwt jwt) {
        AppUser user = userRepository.findByUsername(jwt.getSubject())
                .orElseThrow(() -> new UnauthorizedException("账号不存在"));
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);
        tokenVersions.evict(user.getUsername());
        return ApiResponse.ok(null);
    }
}
