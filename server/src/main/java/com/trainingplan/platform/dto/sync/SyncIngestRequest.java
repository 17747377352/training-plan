package com.trainingplan.platform.dto.sync;

import java.util.List;

/**
 * 采集器上报的同步结果。缺失的数据类型传空列表。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
public record SyncIngestRequest(List<DailyHealthDto> dailyHealth,
                                List<SleepRecordDto> sleep,
                                List<HrvRecordDto> hrv,
                                List<ActivityDto> activities) {
}
