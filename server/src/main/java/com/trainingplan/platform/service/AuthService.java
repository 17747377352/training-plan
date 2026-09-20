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

    AuthTokenDto login(LoginRequest request);

    AuthTokenDto refresh(String refreshToken);

    void logout(String refreshToken);
}

