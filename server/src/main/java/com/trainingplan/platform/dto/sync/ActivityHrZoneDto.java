package com.trainingplan.platform.dto.sync;

/**
 * 采集器上报的活动心率区间。
 *
 * @param zoneNumber      区间序号
 * @param zoneLowBoundary 区间心率下界（bpm）
 * @param secondsInZone   停留秒数
 * @author gongxuesong
 * @date 2026-09-21
 */
public record ActivityHrZoneDto(Integer zoneNumber, Integer zoneLowBoundary, Integer secondsInZone) {
}
