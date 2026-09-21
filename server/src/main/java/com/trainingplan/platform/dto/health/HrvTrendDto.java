package com.trainingplan.platform.dto.health;

import java.time.LocalDate;

/** 单日 HRV 趋势数据。 */
public record HrvTrendDto(
        LocalDate calendarDate,
        Double lastNightAvg,
        Double weeklyAvg,
        String hrvStatus,
        Double baselineLowUpper,
        Double baselineBalancedLow,
        Double baselineBalancedUpper) {
}
