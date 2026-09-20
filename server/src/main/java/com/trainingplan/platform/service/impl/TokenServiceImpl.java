package com.trainingplan.platform.service.impl;

import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.config.SecurityProperties;
import com.trainingplan.platform.dto.auth.AuthTokenDto;
import com.trainingplan.platform.entity.SysUser;
import com.trainingplan.platform.service.TokenService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Spring Security JOSE 和 Redis 的 JWT 令牌服务实现。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Service
public class TokenServiceImpl implements TokenService {

    private static final String REFRESH_KEY_PREFIX = "auth:refresh:";
    private static final String TOKEN_TYPE_CLAIM = "token_type";
    private static final String ACCESS_TOKEN_TYPE = "access";
    private static final String REFRESH_TOKEN_TYPE = "refresh";

    private final JwtEncoder jwtEncoder;
    @Qualifier("refreshJwtDecoder")
    private final JwtDecoder refreshJwtDecoder;
    private final StringRedisTemplate redisTemplate;
    private final SecurityProperties properties;

    public TokenServiceImpl(JwtEncoder jwtEncoder,
                            @Qualifier("refreshJwtDecoder") JwtDecoder refreshJwtDecoder,
                            StringRedisTemplate redisTemplate,
                            SecurityProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.refreshJwtDecoder = refreshJwtDecoder;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public AuthTokenDto issueTokens(SysUser user, List<String> roles) {
        Instant now = Instant.now();
        String accessToken = encodeToken(user, roles, ACCESS_TOKEN_TYPE, now,
                now.plus(properties.accessTokenTtl()));
        String refreshJti = UUID.randomUUID().toString();
        String refreshToken = encodeToken(user, List.of(), REFRESH_TOKEN_TYPE, refreshJti, now,
                now.plus(properties.refreshTokenTtl()));

        redisTemplate.opsForValue().set(
                refreshKey(refreshJti),
                user.getId().toString(),
                properties.refreshTokenTtl().toSeconds(),
                TimeUnit.SECONDS);

        return new AuthTokenDto(
                "Bearer",
                accessToken,
                refreshToken,
                properties.accessTokenTtl().toSeconds());
    }

    @Override
    public Long consumeRefreshToken(String refreshToken) {
        Jwt jwt = decodeRefreshToken(refreshToken);
        String key = refreshKey(jwt.getId());
        String storedUserId = redisTemplate.opsForValue().getAndDelete(key);
        if (storedUserId == null || !storedUserId.equals(jwt.getSubject())) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
    }

    @Override
    public void revokeRefreshToken(String refreshToken) {
        try {
            Jwt jwt = refreshJwtDecoder.decode(refreshToken);
            if (REFRESH_TOKEN_TYPE.equals(jwt.getClaimAsString(TOKEN_TYPE_CLAIM)) && jwt.getId() != null) {
                redisTemplate.delete(refreshKey(jwt.getId()));
            }
        } catch (JwtException ignored) {
            // 退出接口保持幂等，过期或损坏的令牌无需再次处理。
        }
    }

    private String encodeToken(SysUser user, List<String> roles, String tokenType,
                               Instant issuedAt, Instant expiresAt) {
        return encodeToken(user, roles, tokenType, UUID.randomUUID().toString(), issuedAt, expiresAt);
    }

    private String encodeToken(SysUser user, List<String> roles, String tokenType, String jti,
                               Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(user.getId().toString())
                .id(jti)
                .claim("username", user.getUsername())
                .claim(TOKEN_TYPE_CLAIM, tokenType);
        if (!roles.isEmpty()) {
            claims.claim("roles", roles);
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    private Jwt decodeRefreshToken(String refreshToken) {
        try {
            Jwt jwt = refreshJwtDecoder.decode(refreshToken);
            if (!REFRESH_TOKEN_TYPE.equals(jwt.getClaimAsString(TOKEN_TYPE_CLAIM)) || jwt.getId() == null) {
                throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
            }
            return jwt;
        } catch (JwtException exception) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
    }

    private String refreshKey(String jti) {
        return REFRESH_KEY_PREFIX + jti;
    }
}
