package com.trainingplan.platform.dto.activity;

import java.time.LocalDateTime;

/**
 * 活动列表摘要。不包含 GPS、位置与设备标识等敏感或列表非必要字段。
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
public record ActivitySummaryDto(
        Long id,
        String activityTypeKey,
        String activityName,
        LocalDateTime startTime,
        Integer durationSeconds,
        Integer movingDurationSeconds,
        Double distanceMeters,
        Double elevationGain,
        Double averageSpeed,
        Double maxSpeed,
        Integer averageHr,
        Integer maxHr,
        Double calories,
        Double avgPower,
        Double normPower,
        Double max20minPower,
        Double intensityFactor,
        Double trainingStressScore,
        Double avgCadence,
        Double avgLeftBalance,
        Double aerobicTrainingEffect,
        Double anaerobicTrainingEffect,
        String trainingEffectLabel,
        Double activityTrainingLoad,
        Double vo2maxValue) {
}
