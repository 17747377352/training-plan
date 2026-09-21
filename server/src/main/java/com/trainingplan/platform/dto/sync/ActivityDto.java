package com.trainingplan.platform.dto.sync;

/**
 * 采集器上报的骑行活动。
 *
 * <p>字段与 Garmin 活动列表响应一一对应，时间字段为字符串形式。</p>
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
public record ActivityDto(Long garminActivityId,
                          String activityTypeKey,
                          Integer activityTypeId,
                          Integer parentTypeId,
                          String activityName,
                          String startTimeGmt,
                          String startTimeLocal,
                          Double durationSeconds,
                          Double movingDurationSeconds,
                          Double elapsedDurationSeconds,
                          Double distanceMeters,
                          Double elevationGain,
                          Double elevationLoss,
                          Double avgElevation,
                          Double maxElevation,
                          Double minElevation,
                          Double averageSpeed,
                          Double maxSpeed,
                          Double averageHr,
                          Double maxHr,
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
                          Long deviceId) {
}
