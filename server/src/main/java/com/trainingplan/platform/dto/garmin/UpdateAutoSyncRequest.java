package com.trainingplan.platform.dto.garmin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 修改 Garmin 账号自动同步开关请求。
 *
 * @param syncEnabled 是否允许自动同步：0 暂停，1 启用
 * @author gongxuesong
 * @date 2026-09-20
 */
public record UpdateAutoSyncRequest(
        @NotNull(message = "自动同步开关不能为空")
        @Min(value = 0, message = "自动同步开关只能为0或1")
        @Max(value = 1, message = "自动同步开关只能为0或1")
        Integer syncEnabled) {
}
