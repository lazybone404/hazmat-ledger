package com.lazybone.hazmat.service;

import com.lazybone.hazmat.common.BusinessException;
import com.lazybone.hazmat.common.ResourceNotFoundException;
import com.lazybone.hazmat.dto.CreateUserRequest;
import com.lazybone.hazmat.dto.UpdateUserRequest;
import com.lazybone.hazmat.dto.UserAdminView;
import com.lazybone.hazmat.entity.AppUser;
import com.lazybone.hazmat.entity.UserRole;
import com.lazybone.hazmat.repository.AppUserRepository;
import com.lazybone.hazmat.security.TokenVersionService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 账号管理：建号、启停、改角色、重置密码。
 *
 * <p>所有会改变「这个 token 还能不能代表这个人」的操作都自增
 * {@code token_version}：停用、改角色、重置密码。改显示名不升版本——
 * 显示名不参与权限判断，为了它把人踢下线没有意义。
 *
 * <p>「最后一个管理员」保护写在服务层而不是控制器：以后如果加批量导入或 CLI，
 * 它们也应该受同一条规则约束。
 */
@Service
public class UserAdminService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenVersionService tokenVersions;

    public UserAdminService(AppUserRepository userRepository,
                            PasswordEncoder passwordEncoder,
                            TokenVersionService tokenVersions) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenVersions = tokenVersions;
    }

    @Transactional(readOnly = true)
    public List<UserAdminView> list() {
        return userRepository.findAllByOrderByUsernameAsc().stream()
                .map(UserAdminView::from)
                .toList();
    }

    @Transactional
    public UserAdminView create(CreateUserRequest request) {
        String username = request.username().trim();
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException("用户名已存在: " + username);
        }
        AppUser user = userRepository.save(AppUser.builder()
                .username(username)
                .displayName(request.displayName().trim())
                .role(request.role())
                .passwordHash(passwordEncoder.encode(request.password()))
                .enabled(true)
                .build());
        return UserAdminView.from(user);
    }

    @Transactional
    public UserAdminView update(Long id, UpdateUserRequest request) {
        AppUser user = find(id);

        boolean roleChanged = request.role() != null && request.role() != user.getRole();
        boolean enabledChanged = request.enabled() != null && request.enabled() != user.isEnabled();

        if (roleChanged || enabledChanged) {
            guardLastAdmin(user, request);
        }
        if (request.displayName() != null && !request.displayName().isBlank()) {
            user.setDisplayName(request.displayName().trim());
        }
        if (roleChanged) {
            user.setRole(request.role());
        }
        if (enabledChanged) {
            user.setEnabled(request.enabled());
        }
        // 权限相关变更才撤销旧 token；显示名变化不踢人
        if (roleChanged || enabledChanged) {
            user.setTokenVersion(user.getTokenVersion() + 1);
        }
        AppUser saved = userRepository.save(user);
        tokenVersions.evict(user.getUsername());
        return UserAdminView.from(saved);
    }

    @Transactional
    public void resetPassword(Long id, String newPassword) {
        AppUser user = find(id);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);
        tokenVersions.evict(user.getUsername());
    }

    /**
     * 不允许把「最后一个启用中的管理员」停用或降级：
     * 一旦发生，系统就再没有任何人能进管理接口，只能改库恢复。
     */
    private void guardLastAdmin(AppUser user, UpdateUserRequest request) {
        if (user.getRole() != UserRole.ADMIN || !user.isEnabled()) {
            return;
        }
        boolean losesAdmin = (request.role() != null && request.role() != UserRole.ADMIN)
                || Boolean.FALSE.equals(request.enabled());
        if (losesAdmin && userRepository.countByRoleAndEnabledTrue(UserRole.ADMIN) <= 1) {
            throw new BusinessException("不能停用或降级最后一个启用中的管理员");
        }
    }

    private AppUser find(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("用户不存在: " + id));
    }
}
