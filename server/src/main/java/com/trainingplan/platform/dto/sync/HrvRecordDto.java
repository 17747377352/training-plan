package com.trainingplan.platform.dto.sync;

/**
 * 采集器上报的 HRV 状态记录。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
public record HrvRecordDto(String calendarDate,
                           Double lastNightAvg,
                           Double weeklyAvg,
                           String status,
                           Double baselineLowUpper,
                           Double baselineBalancedLow,
                           Double baselineBalancedUpper) {
}
