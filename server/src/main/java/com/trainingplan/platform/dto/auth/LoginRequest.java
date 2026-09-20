package com.trainingplan.platform.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户登录请求，账号字段支持用户名或邮箱。
 *
 * @param account  用户名或邮箱
 * @param password 明文密码
 * @author gongxuesong
 * @date 2026-09-20
 */
public record LoginRequest(
        @NotBlank @Size(max = 128) String account,
        @NotBlank @Size(max = 64) String password) {
}

