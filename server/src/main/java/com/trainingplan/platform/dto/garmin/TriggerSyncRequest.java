package com.trainingplan.platform.dto.garmin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 触发同步请求。
 *
 * @param days 回溯天数，1 表示仅当天；不传按 1 处理
 * @author gongxuesong
 * @date 2026-09-20
 */
public record TriggerSyncRequest(
        @Min(value = 1, message = "回溯天数不能小于1")
        @Max(value = 365, message = "回溯天数不能超过365")
        Integer days) {
}
