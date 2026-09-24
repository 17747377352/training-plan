package com.trainingplan.platform.dto.garmin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 本机采集助手的配对请求，不接收 Garmin 密码、Cookie 或 DI 令牌。
 *
 * @param code 平台签发的一次性配对码
 * @param email 用于账号去重的 Garmin 邮箱，服务端只保存摘要及脱敏展示值
 * @param region Garmin 站点
 * @author gongxuesong
 * @date 2026-09-24
 */
public record BrowserUploadPairRequest(
        @NotBlank @Size(max = 32) String code,
        @NotBlank @Email @Size(max = 128) String email,
        @NotBlank @Pattern(regexp = "GLOBAL|CN") String region) {
}
