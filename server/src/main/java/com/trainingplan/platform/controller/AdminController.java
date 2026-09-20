package com.trainingplan.platform.controller;

import com.trainingplan.platform.common.api.PageResult;
import com.trainingplan.platform.common.api.Result;
import com.trainingplan.platform.dto.admin.AdminUserDto;
import com.trainingplan.platform.dto.admin.AdminUserQuery;
import com.trainingplan.platform.dto.admin.UpdateUserStatusRequest;
import com.trainingplan.platform.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员的平台用户管理接口，仅 ADMIN 角色可访问。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminUserService adminUserService;

    /**
     * 分页查询平台用户。
     *
     * @param query 分页与过滤条件
     * @return 用户分页结果
     */
    @GetMapping("/users")
    public Result<PageResult<AdminUserDto>> listUsers(@Valid AdminUserQuery query) {
        return Result.success(adminUserService.listUsers(query));
    }

    /**
     * 启用或禁用平台用户。
     *
     * @param id      目标用户 ID
     * @param request 目标状态
     * @param jwt     当前登录管理员令牌
     * @return 空响应
     */
    @PutMapping("/users/{id}/status")
    public Result<Void> updateUserStatus(@PathVariable Long id,
                                         @Valid @RequestBody UpdateUserStatusRequest request,
                                         @AuthenticationPrincipal Jwt jwt) {
        adminUserService.updateUserStatus(id, request.status(), Long.valueOf(jwt.getSubject()));
        return Result.success(null);
    }
}
