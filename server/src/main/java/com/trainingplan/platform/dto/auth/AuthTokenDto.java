package com.trainingplan.platform.dto.auth;

/**
 * 登录或刷新后的令牌响应。
 *
 * @param tokenType            令牌类型
 * @param accessToken          访问令牌
 * @param refreshToken         刷新令牌
 * @param accessTokenExpiresIn 访问令牌剩余秒数
 * @author gongxuesong
 * @date 2026-09-20
 */
public record AuthTokenDto(
        String tokenType,
        String accessToken,
        String refreshToken,
        long accessTokenExpiresIn) {
}

