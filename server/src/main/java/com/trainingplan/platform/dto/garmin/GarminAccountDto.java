package com.trainingplan.platform.dto.garmin;

import java.time.LocalDateTime;

/**
 * 已绑定 Garmin 账号的展示信息，不包含任何 Token 字段。
 *
 * @param id            账号 ID
 * @param region        站点区域：GLOBAL 国际站，CN 中国站
 * @param emailMasked   脱敏邮箱
 * @param authStatus    认证状态
 * @param syncEnabled   是否允许自动同步
 * @param lastSyncTime  最近同步时间
 * @param createTime    绑定时间
 * @author gongxuesong
 * @date 2026-09-20
 */
public record GarminAccountDto(Long id,
                               String region,
                               String emailMasked,
                               String authStatus,
                               Integer syncEnabled,
                               LocalDateTime lastSyncTime,
                               LocalDateTime createTime) {
}
