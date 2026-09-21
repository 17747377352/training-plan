package com.trainingplan.platform.dto.health;

import java.time.LocalDate;

/** 单日健康趋势数据。 */
public record DailyHealthTrendDto(
        LocalDate calendarDate,
        Integer steps,
        Double distanceMeters,
        Double totalKilocalories,
        Double activeKilocalories,
        Integer restingHeartRate,
        Integer minHeartRate,
        Integer maxHeartRate,
        Integer averageStressLevel,
        Integer bodyBatteryHighest,
        Integer bodyBatteryLowest) {
}
