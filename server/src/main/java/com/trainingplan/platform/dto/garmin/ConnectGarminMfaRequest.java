package com.trainingplan.platform.dto.garmin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 提交 Garmin MFA 验证码请求。
 *
 * @param loginSessionId 连接接口返回的 MFA 会话 ID
 * @param mfaCode        Garmin 发送的验证码
 * @author gongxuesong
 * @date 2026-09-20
 */
public record ConnectGarminMfaRequest(
        @NotBlank @Size(max = 64) String loginSessionId,
        @NotBlank @Size(max = 16) String mfaCode) {
}
