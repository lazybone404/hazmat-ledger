package com.lazybone.hazmat.controller;

import com.lazybone.hazmat.common.ApiResponse;
import com.lazybone.hazmat.dto.CreateUserRequest;
import com.lazybone.hazmat.dto.ResetPasswordRequest;
import com.lazybone.hazmat.dto.UpdateUserRequest;
import com.lazybone.hazmat.dto.UserAdminView;
import com.lazybone.hazmat.service.UserAdminService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 账号管理。全部限 ADMIN——这里能改角色和密码，泄露一个普通账号不该导致提权。
 */
@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserAdminController {

    private final UserAdminService userAdminService;

    public UserAdminController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    @GetMapping
    public ApiResponse<List<UserAdminView>> list() {
        return ApiResponse.ok(userAdminService.list());
    }

    @PostMapping
    public ApiResponse<UserAdminView> create(@Valid @RequestBody CreateUserRequest request) {
        return ApiResponse.ok(userAdminService.create(request));
    }

    @PatchMapping("/{id}")
    public ApiResponse<UserAdminView> update(@PathVariable Long id,
                                             @Valid @RequestBody UpdateUserRequest request) {
        return ApiResponse.ok(userAdminService.update(id, request));
    }

    @PostMapping("/{id}/password")
    public ApiResponse<Void> resetPassword(@PathVariable Long id,
                                           @Valid @RequestBody ResetPasswordRequest request) {
        userAdminService.resetPassword(id, request.newPassword());
        return ApiResponse.ok(null);
    }
}
