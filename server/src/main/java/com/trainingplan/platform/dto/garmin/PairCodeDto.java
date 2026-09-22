package com.trainingplan.platform.dto.garmin;

/**
 * 桌面助手配对码。
 *
 * @param code             配对码，用户在桌面助手里输入
 * @param expiresInSeconds 有效期（秒）
 * @author gongxuesong
 * @date 2026-09-22
 */
public record PairCodeDto(String code, long expiresInSeconds) {
}
