package com.trainingplan.platform.dto.checkin;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 一条每日手工打卡。
 *
 * @param calendarDate 数据归属日期
 * @param weightKg     体重（千克）
 * @param rpe          主观疲劳度 1-10
 * @param note         备注
 * @author gongxuesong
 * @date 2026-09-21
 */
public record DailyCheckinDto(LocalDate calendarDate, BigDecimal weightKg, Integer rpe, String note) {
}
