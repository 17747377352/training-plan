package com.trainingplan.platform.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwsHeader;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 令牌类型隔离测试。
 *
 * <p>资源服务器只应接受 access token，refresh token 只能由令牌服务使用，
 * 避免刷新令牌被当作接口凭证直接访问业务接口。</p>
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
class JwtConfigTest {

    private static final String SECRET = "unit-test-jwt-secret-key-with-32-plus-characters";
    private static final String ISSUER = "training-plan-server";
    private static final String OTHER_SECRET = "another-unit-test-secret-key-with-32-chars";
    private static final String TOKEN_TYPE_CLAIM = "token_type";

    private JwtConfig jwtConfig;
    private SecurityProperties properties;
    private JwtEncoder jwtEncoder;
    /** 资源服务器使用的解码器，即 {@code @Primary} 的 jwtDecoder。 */
    private JwtDecoder resourceServerDecoder;
    /** 仅令牌服务使用的刷新令牌解码器。 */
    private JwtDecoder refreshTokenDecoder;

    @BeforeEach
    void setUp() {
        jwtConfig = new JwtConfig();
        properties = new SecurityProperties(
                SECRET, ISSUER, Duration.ofMinutes(15), Duration.ofDays(30));
        SecretKey secretKey = jwtConfig.jwtSecretKey(properties);
        jwtEncoder = jwtConfig.jwtEncoder(secretKey);
        resourceServerDecoder = jwtConfig.jwtDecoder(secretKey, properties);
        refreshTokenDecoder = jwtConfig.refreshJwtDecoder(secretKey, properties);
    }

    @Test
    void shouldAcceptAccessTokenWithResourceServerDecoder() {
        String accessToken = encodeToken("access", "1", Instant.now().plus(Duration.ofMinutes(15)));

        assertThat(resourceServerDecoder.decode(accessToken).getClaimAsString(TOKEN_TYPE_CLAIM))
                .isEqualTo("access");
    }

    @Test
    void shouldAcceptRefreshTokenWithRefreshDecoder() {
        String refreshToken = encodeToken("refresh", "1", Instant.now().plus(Duration.ofDays(30)));

        assertThat(refreshTokenDecoder.decode(refreshToken).getClaimAsString(TOKEN_TYPE_CLAIM))
                .isEqualTo("refresh");
    }

    @Test
    void shouldRejectRefreshTokenOnResourceServerDecoder() {
        String refreshToken = encodeToken("refresh", "1", Instant.now().plus(Duration.ofDays(30)));

        assertThatThrownBy(() -> resourceServerDecoder.decode(refreshToken))
                .isInstanceOf(JwtValidationException.class)
                .satisfies(exception -> assertThat(((JwtValidationException) exception).getErrors())
                        .extracting(OAuth2Error::getErrorCode)
                        .contains("invalid_token"));
    }

    @Test
    void shouldRejectAccessTokenOnRefreshDecoder() {
        String accessToken = encodeToken("access", "1", Instant.now().plus(Duration.ofMinutes(15)));

        assertThatThrownBy(() -> refreshTokenDecoder.decode(accessToken))
                .isInstanceOf(JwtValidationException.class)
                .satisfies(exception -> assertThat(((JwtValidationException) exception).getErrors())
                        .extracting(OAuth2Error::getErrorCode)
                        .contains("invalid_token"));
    }

    @Test
    void shouldRejectTokenWithoutTokenTypeClaim() {
        String tokenWithoutType = jwtEncoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(),
                        JwtClaimsSet.builder()
                                .issuer(ISSUER)
                                .issuedAt(Instant.now())
                                .expiresAt(Instant.now().plus(Duration.ofMinutes(15)))
                                .subject("1")
                                .id(UUID.randomUUID().toString())
                                .build()))
                .getTokenValue();

        assertThatThrownBy(() -> resourceServerDecoder.decode(tokenWithoutType))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void shouldRejectTokenSignedWithAnotherSecret() {
        SecretKey otherKey = jwtConfig.jwtSecretKey(new SecurityProperties(
                OTHER_SECRET, ISSUER, Duration.ofMinutes(15), Duration.ofDays(30)));
        JwtEncoder otherEncoder = jwtConfig.jwtEncoder(otherKey);
        String foreignToken = otherEncoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(),
                        JwtClaimsSet.builder()
                                .issuer(ISSUER)
                                .issuedAt(Instant.now())
                                .expiresAt(Instant.now().plus(Duration.ofMinutes(15)))
                                .subject("1")
                                .id(UUID.randomUUID().toString())
                                .claim(TOKEN_TYPE_CLAIM, "access")
                                .build()))
                .getTokenValue();

        assertThatThrownBy(() -> resourceServerDecoder.decode(foreignToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void shouldRejectSecretShorterThan32Characters() {
        SecurityProperties weakProperties = new SecurityProperties(
                "too-short", ISSUER, Duration.ofMinutes(15), Duration.ofDays(30));

        assertThatThrownBy(() -> jwtConfig.jwtSecretKey(weakProperties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");
    }

    private String encodeToken(String tokenType, String subject, Instant expiresAt) {
        return jwtEncoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(),
                        JwtClaimsSet.builder()
                                .issuer(ISSUER)
                                .issuedAt(Instant.now())
                                .expiresAt(expiresAt)
                                .subject(subject)
                                .id(UUID.randomUUID().toString())
                                .claim(TOKEN_TYPE_CLAIM, tokenType)
                                .build()))
                .getTokenValue();
    }
}
