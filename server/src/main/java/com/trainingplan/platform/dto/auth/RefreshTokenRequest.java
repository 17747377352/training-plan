package com.trainingplan.platform.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * 刷新访问令牌请求。
 *
 * @param refreshToken 刷新令牌
 * @author gongxuesong
 * @date 2026-09-20
 */
public record RefreshTokenRequest(@NotBlank String refreshToken) {
}

