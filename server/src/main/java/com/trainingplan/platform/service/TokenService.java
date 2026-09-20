package com.trainingplan.platform.service;

import com.trainingplan.platform.dto.auth.AuthTokenDto;
import com.trainingplan.platform.entity.SysUser;

import java.util.List;

/**
 * JWT 令牌签发、轮换和吊销服务。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
public interface TokenService {

    /**
     * 为已认证用户签发访问令牌和刷新令牌。
     *
     * @param user  用户
     * @param roles 角色编码
     * @return 令牌对
     */
    AuthTokenDto issueTokens(SysUser user, List<String> roles);

    /**
     * 校验并消费刷新令牌，刷新令牌只能使用一次。
     *
     * @param refreshToken 刷新令牌
     * @return 用户 ID
     */
    Long consumeRefreshToken(String refreshToken);

    /**
     * 吊销刷新令牌。重复吊销保持幂等。
     *
     * @param refreshToken 刷新令牌
     */
    void revokeRefreshToken(String refreshToken);
}

