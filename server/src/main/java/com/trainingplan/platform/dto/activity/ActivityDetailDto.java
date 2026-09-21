package com.trainingplan.platform.dto.activity;

import java.time.LocalDateTime;

/**
 * 当前用户的单条活动详情，覆盖 activity 表已入库的全部字段。
 *
 * <p>活动详情不包含采集端从未入库的 GPS 坐标、位置名和账号姓名。</p>
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
public record ActivityDetailDto(
        Long id,
        Long garminAccountId,
        String garminActivityId,
        String activityTypeKey,
        Integer activityTypeId,
        Integer parentTypeId,
        String activityName,
        LocalDateTime startTimeGmt,
        LocalDateTime startTimeLocal,
        Integer durationSeconds,
        Integer movingDurationSeconds,
        Integer elapsedDurationSeconds,
        Double distanceMeters,
        Double elevationGain,
        Double elevationLoss,
        Double avgElevation,
        Double maxElevation,
        Double minElevation,
        Double averageSpeed,
        Double maxSpeed,
        Integer averageHr,
        Integer maxHr,
        Double calories,
        Double bmrCalories,
        Double avgPower,
        Double maxPower,
        Double normPower,
        Double max20minPower,
        Double intensityFactor,
        Double trainingStressScore,
        Double avgCadence,
        Double maxCadence,
        Double avgLeftBalance,
        Double aerobicTrainingEffect,
        Double anaerobicTrainingEffect,
        String trainingEffectLabel,
        Double activityTrainingLoad,
        Double powerZone1Seconds,
        Double powerZone2Seconds,
        Double powerZone3Seconds,
        Double powerZone4Seconds,
        Double powerZone5Seconds,
        Double powerZone6Seconds,
        Double powerZone7Seconds,
        Integer lapCount,
        Double strokes,
        Double avgRespirationRate,
        Double minTemperature,
        Double maxTemperature,
        Double vo2maxValue,
        String deviceId,
        LocalDateTime createTime,
        LocalDateTime updateTime) {
}
