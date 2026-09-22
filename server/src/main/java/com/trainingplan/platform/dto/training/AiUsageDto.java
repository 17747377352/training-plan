package com.trainingplan.platform.dto.training;

import java.time.LocalDate;

/**
 * AI 生成的当日用量与配额。
 *
 * @param calendarDate    归属日期
 * @param limitPerDay     每用户每日调用上限
 * @param usedToday       今日已消耗次数（不含指纹复用）
 * @param remainingToday  今日剩余次数
 * @param promptTokens    今日输入 Token 合计
 * @param completionTokens 今日输出 Token 合计
 * @param totalTokens     今日合计 Token
 * @param reusedToday     今日指纹复用次数（不消耗配额）
 * @author gongxuesong
 * @date 2026-09-21
 */
public record AiUsageDto(LocalDate calendarDate,
                         int limitPerDay,
                         long usedToday,
                         long remainingToday,
                         long promptTokens,
                         long completionTokens,
                         long totalTokens,
                         long reusedToday) {
}
