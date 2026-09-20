package com.trainingplan.platform.dto.sync;

/**
 * 采集器上报的睡眠记录，时间字段为 GMT。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
public record SleepRecordDto(String calendarDate,
                             String sleepStartGmt,
                             String sleepEndGmt,
                             Integer sleepTimeSeconds,
                             Integer deepSleepSeconds,
                             Integer lightSleepSeconds,
                             Integer remSleepSeconds,
                             Integer awakeSleepSeconds,
                             Integer sleepScore,
                             Double avgSleepHrv,
                             Double avgSpo2,
                             Double avgRespiration) {
}
