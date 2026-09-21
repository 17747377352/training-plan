package com.trainingplan.platform.dto.training;

import java.time.LocalDate;

/**
 * 用户训练目标。
 *
 * @param goalType       目标类型：POWER/MUSCLE/ENDURANCE/GENERAL/OTHER
 * @param goalLabel      目标类型的中文名
 * @param targetDate     目标日期
 * @param daysToTarget   距目标日期的天数，未设置日期时为空
 * @param weeklySessions 每周可训练次数
 * @param weeklyMinutes  每周可投入总时长（分钟）
 * @param description    用户手动补充的描述
 * @author gongxuesong
 * @date 2026-09-21
 */
public record TrainingGoalDto(String goalType,
                              String goalLabel,
                              LocalDate targetDate,
                              Long daysToTarget,
                              Integer weeklySessions,
                              Integer weeklyMinutes,
                              String description) {
}
