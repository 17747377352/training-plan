package com.trainingplan.platform.controller;

import com.trainingplan.platform.common.api.Result;
import com.trainingplan.platform.dto.auth.AuthTokenDto;
import com.trainingplan.platform.dto.auth.LoginRequest;
import com.trainingplan.platform.dto.auth.LogoutRequest;
import com.trainingplan.platform.dto.auth.RefreshTokenRequest;
import com.trainingplan.platform.dto.auth.RegisterRequest;
import com.trainingplan.platform.dto.user.UserProfileDto;
import com.trainingplan.platform.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台用户注册、登录与令牌管理接口。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 查询是否开放自助注册。
     *
     * <p>公开接口，只返回一个布尔值，供注册页在提交前如实告知；
     * 不泄露任何账号信息。</p>
     *
     * @return 是否允许注册
     */
    @GetMapping("/registration-enabled")
    public Result<Boolean> registrationEnabled() {
        return Result.success(authService.isRegistrationEnabled());
    }

    @PostMapping("/register")
    public Result<UserProfileDto> register(@Valid @RequestBody RegisterRequest request) {
        return Result.success(authService.register(request));
    }

    @PostMapping("/login")
    public Result<AuthTokenDto> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(authService.login(request));
    }

    @PostMapping("/refresh")
    public Result<AuthTokenDto> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return Result.success(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
        return Result.success(null);
    }
}
