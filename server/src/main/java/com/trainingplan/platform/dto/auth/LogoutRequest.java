package com.trainingplan.platform.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * 用户退出请求。
 *
 * @param refreshToken 需要吊销的刷新令牌
 * @author gongxuesong
 * @date 2026-09-20
 */
public record LogoutRequest(@NotBlank String refreshToken) {
}

