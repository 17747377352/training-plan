package com.trainingplan.platform.dto.sync;

/**
 * 采集器上报的每日健康汇总。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
public record DailyHealthDto(String calendarDate,
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
