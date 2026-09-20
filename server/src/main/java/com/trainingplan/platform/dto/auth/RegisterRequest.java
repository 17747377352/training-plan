package com.trainingplan.platform.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 用户注册请求。
 *
 * @param username 用户名
 * @param email    邮箱
 * @param password 明文密码，仅在请求处理期间存在
 * @author gongxuesong
 * @date 2026-09-20
 */
public record RegisterRequest(
        @NotBlank
        @Pattern(regexp = "^[a-zA-Z0-9_]{4,32}$", message = "用户名只能包含字母、数字和下划线，长度4至32位")
        String username,
        @NotBlank @Email @Size(max = 128) String email,
        @NotBlank @Size(min = 8, max = 64) String password) {
}

