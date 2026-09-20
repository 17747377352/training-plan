package com.trainingplan.platform.dto.garmin;

/**
 * Garmin 连接结果。
 *
 * @param status         CONNECTED 表示已连接，MFA_REQUIRED 表示需要提交验证码
 * @param loginSessionId MFA 会话 ID，仅当 status 为 MFA_REQUIRED 时返回
 * @param account       已绑定的账号信息，仅当 status 为 CONNECTED 时返回
 * @author gongxuesong
 * @date 2026-09-20
 */
public record GarminConnectResultDto(String status, String loginSessionId, GarminAccountDto account) {

    public static final String STATUS_CONNECTED = "CONNECTED";
    public static final String STATUS_MFA_REQUIRED = "MFA_REQUIRED";

    public static GarminConnectResultDto connected(GarminAccountDto account) {
        return new GarminConnectResultDto(STATUS_CONNECTED, null, account);
    }

    public static GarminConnectResultDto mfaRequired(String loginSessionId) {
        return new GarminConnectResultDto(STATUS_MFA_REQUIRED, loginSessionId, null);
    }
}
