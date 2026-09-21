package com.trainingplan.platform.dto.sync;

import java.util.List;
import java.util.Map;

/**
 * 采集器上报的同步结果。缺失的数据类型传空列表或空 Map。
 *
 * @param dailyHealth     每日健康
 * @param sleep           睡眠
 * @param hrv             HRV
 * @param activities      骑行活动
 * @param trainingStatus  每日训练状态与负荷
 * @param ftpHistory      骑行 FTP 历史
 * @param activityHrZones 活动心率区间，键为 Garmin 活动 ID
 * @author gongxuesong
 * @date 2026-09-20
 */
public record SyncIngestRequest(List<DailyHealthDto> dailyHealth,
                                List<SleepRecordDto> sleep,
                                List<HrvRecordDto> hrv,
                                List<ActivityDto> activities,
                                List<TrainingStatusDto> trainingStatus,
                                List<FtpHistoryDto> ftpHistory,
                                Map<Long, List<ActivityHrZoneDto>> activityHrZones) {
}
