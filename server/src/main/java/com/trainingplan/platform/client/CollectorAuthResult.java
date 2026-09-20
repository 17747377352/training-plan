package com.trainingplan.platform.client;

/**
 * Collector 返回的 Garmin 认证结果。
 *
 * @param status         CONNECTED 或 MFA_REQUIRED
 * @param tokenJson      Garmin Token JSON，仅 CONNECTED 时返回，不得写入日志
 * @param loginSessionId MFA 会话 ID，仅 MFA_REQUIRED 时返回
 * @param message        失败时的脱敏提示
 * @author gongxuesong
 * @date 2026-09-20
 */
public record CollectorAuthResult(String status, String tokenJson, String loginSessionId, String message) {

    public static final String STATUS_CONNECTED = "CONNECTED";
    public static final String STATUS_MFA_REQUIRED = "MFA_REQUIRED";
    public static final String STATUS_MFA_INVALID = "MFA_INVALID";
    public static final String STATUS_INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    public static final String STATUS_RATE_LIMITED = "RATE_LIMITED";
    public static final String STATUS_FAILED = "FAILED";

    public boolean isConnected() {
        return STATUS_CONNECTED.equals(status);
    }

    public boolean isMfaRequired() {
        return STATUS_MFA_REQUIRED.equals(status);
    }
}
