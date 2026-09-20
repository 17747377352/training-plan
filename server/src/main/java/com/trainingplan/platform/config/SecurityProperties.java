package com.trainingplan.platform.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * JWT 与敏感数据加密配置。
 *
 * <p>{@code tokenCipherKey} 为 Base64 编码的 32 字节 AES 密钥，仅用于加密 Garmin Token，
 * 与 JWT 签名密钥相互独立，便于分别轮换。</p>
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Validated
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        @NotBlank String jwtSecret,
        @NotBlank String issuer,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl,
        @NotBlank String tokenCipherKey) {
}
