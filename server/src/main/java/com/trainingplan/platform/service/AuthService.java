package com.trainingplan.platform.service;

import com.trainingplan.platform.dto.auth.AuthTokenDto;
import com.trainingplan.platform.dto.auth.LoginRequest;
import com.trainingplan.platform.dto.auth.RegisterRequest;
import com.trainingplan.platform.dto.user.UserProfileDto;

/**
 * 用户注册和认证服务。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
public interface AuthService {

    UserProfileDto register(RegisterRequest request);

    /**
     * 是否开放自助注册。
     *
     * <p>默认关闭：开放注册意味着任何人都能创建账号并调用付费的 AI 接口。</p>
     *
     * @return 是否允许注册
     */
    boolean isRegistrationEnabled();

    AuthTokenDto login(LoginRequest request);

    AuthTokenDto refresh(String refreshToken);

    void logout(String refreshToken);
}

