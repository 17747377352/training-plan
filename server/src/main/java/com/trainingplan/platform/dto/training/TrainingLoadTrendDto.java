package com.trainingplan.platform.dto.training;

import java.time.LocalDate;

/**
 * 某一天的训练状态与负荷，用于趋势展示。
 *
 * @param calendarDate       数据归属日期
 * @param trainingStatusPhrase 训练状态短语
 * @param acwrPercent        急性慢性负荷比（百分比）
 * @param acwrStatus         ACWR 区间状态
 * @param acwrRatio          急性/慢性负荷比值
 * @param acuteLoad          7 天急性负荷
 * @param chronicLoad        28 天慢性负荷
 * @param chronicLoadMin     慢性负荷合理区间下界
 * @param chronicLoadMax     慢性负荷合理区间上界
 * @param loadAerobicLow     低强度有氧负荷
 * @param loadAerobicLowTargetMin 低强度有氧目标下界
 * @param loadAerobicLowTargetMax 低强度有氧目标上界
 * @param loadAerobicHigh    高强度有氧负荷
 * @param loadAerobicHighTargetMin 高强度有氧目标下界
 * @param loadAerobicHighTargetMax 高强度有氧目标上界
 * @param loadAnaerobic      无氧负荷
 * @param loadAnaerobicTargetMin 无氧目标下界
 * @param loadAnaerobicTargetMax 无氧目标上界
 * @param balanceFeedbackPhrase 负荷平衡诊断
 * @param vo2maxValue        最新 VO2max
 * @param fitnessAge         体能年龄
 * @author gongxuesong
 * @date 2026-09-21
 */
public record TrainingLoadTrendDto(LocalDate calendarDate,
                                   String trainingStatusPhrase,
                                   Integer acwrPercent,
                                   String acwrStatus,
                                   Double acwrRatio,
                                   Integer acuteLoad,
                                   Integer chronicLoad,
                                   Double chronicLoadMin,
                                   Double chronicLoadMax,
                                   Double loadAerobicLow,
                                   Integer loadAerobicLowTargetMin,
                                   Integer loadAerobicLowTargetMax,
                                   Double loadAerobicHigh,
                                   Integer loadAerobicHighTargetMin,
                                   Integer loadAerobicHighTargetMax,
                                   Double loadAnaerobic,
                                   Integer loadAnaerobicTargetMin,
                                   Integer loadAnaerobicTargetMax,
                                   String balanceFeedbackPhrase,
                                   Double vo2maxValue,
                                   Integer fitnessAge) {
}
