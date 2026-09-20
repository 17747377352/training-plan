package com.trainingplan.platform.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Collector 内网调用配置。
 *
 * @param baseUrl      Collector 内网地址
 * @param serviceToken 内部服务凭据，仅用于服务间鉴权
 * @param timeout      单次调用超时时间
 * @author gongxuesong
 * @date 2026-09-20
 */
@Validated
@ConfigurationProperties(prefix = "app.collector")
public record CollectorProperties(
        @NotBlank String baseUrl,
        @NotBlank String serviceToken,
        @NotNull Duration timeout) {
}
