package com.trainingplan.platform.dto.garmin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 连接 Garmin 账号请求。
 *
 * <p>密码仅在本次请求内使用，平台不持久化；请求处理完成后不得写入日志。</p>
 *
 * @param email    Garmin 登录邮箱
 * @param password Garmin 登录密码
 * @param region   站点区域：GLOBAL 国际站，CN 中国站
 * @author gongxuesong
 * @date 2026-09-20
 */
public record ConnectGarminRequest(
        @NotBlank @Email @Size(max = 128) String email,
        @NotBlank @Size(max = 128) String password,
        @NotBlank @Pattern(regexp = "^(GLOBAL|CN)$", message = "区域只能为 GLOBAL 或 CN") String region) {
}
