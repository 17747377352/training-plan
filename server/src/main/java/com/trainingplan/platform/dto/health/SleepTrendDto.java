package com.trainingplan.platform.dto.health;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 单次睡眠趋势数据，同一日可包含主睡眠和午睡。 */
public record SleepTrendDto(
        LocalDate calendarDate,
        LocalDateTime sleepStartGmt,
        LocalDateTime sleepEndGmt,
        Integer sleepTimeSeconds,
        Integer deepSleepSeconds,
        Integer lightSleepSeconds,
        Integer remSleepSeconds,
        Integer awakeSleepSeconds,
        Integer sleepScore,
        Double avgSleepHrv,
        Double avgSpo2,
        Double avgRespiration) {
}
