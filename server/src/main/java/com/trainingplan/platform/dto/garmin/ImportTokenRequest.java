package com.trainingplan.platform.dto.garmin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 导入已有 Garmin 令牌的请求。
 *
 * <p>用于无法通过程序登录（例如 Garmin 对登录端点做限流或人机验证）时，
 * 由用户在浏览器侧取得令牌后交给平台。平台会先向采集器校验令牌可用，
 * 校验通过才加密入库。</p>
 *
 * @param email     Garmin 登录邮箱，用于生成账号摘要与脱敏展示
 * @param tokenJson Garmin 令牌 JSON，形如 {@code {"di_token":..,"di_refresh_token":..,"di_client_id":..}}
 * @param region    站点区域：GLOBAL 国际站，CN 中国站
 * @author gongxuesong
 * @date 2026-09-20
 */
public record ImportTokenRequest(
        @NotBlank @Email @Size(max = 128) String email,
        @NotBlank @Size(max = 8192) String tokenJson,
        @NotBlank @Pattern(regexp = "^(GLOBAL|CN)$", message = "区域只能为 GLOBAL 或 CN") String region) {
}
