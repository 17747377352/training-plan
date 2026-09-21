package com.trainingplan.platform.dto.sync;

/**
 * 采集器上报的一次午睡。
 *
 * @param calendarDate 数据归属日期
 * @param napStartGmt  午睡开始（GMT ISO）
 * @param napEndGmt    午睡结束（GMT ISO）
 * @param napSeconds   时长（秒）
 * @param napFeedback  Garmin 的评价短语
 * @param napSource    Garmin 的来源标记
 * @author gongxuesong
 * @date 2026-09-21
 */
public record NapRecordDto(String calendarDate,
                           String napStartGmt,
                           String napEndGmt,
                           Integer napSeconds,
                           String napFeedback,
                           Integer napSource) {
}
