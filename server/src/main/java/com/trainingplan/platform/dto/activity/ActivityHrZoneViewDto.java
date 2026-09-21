package com.trainingplan.platform.dto.activity;

/**
 * 活动心率区间分布中的一段。
 *
 * @param zoneNumber      区间序号
 * @param zoneLowBoundary 区间心率下界（bpm）
 * @param secondsInZone   停留秒数
 * @author gongxuesong
 * @date 2026-09-21
 */
public record ActivityHrZoneViewDto(Integer zoneNumber, Integer zoneLowBoundary, Integer secondsInZone) {
}
