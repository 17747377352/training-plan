package com.trainingplan.platform.dto.sync;

import java.util.List;
import java.util.Map;

/**
 * 采集器上报的同步结果。缺失的数据类型传空列表或空 Map。
 *
 * @param dailyHealth     每日健康
 * @param sleep           睡眠
 * @param naps            午睡（不参与判灯，仅记录）
 * @param hrv             HRV
 * @param activities      骑行活动
 * @param trainingStatus  每日训练状态与负荷
 * @param ftpHistory      骑行 FTP 历史
 * @param activityHrZones 活动心率区间，键为 Garmin 活动 ID
 * @param thresholdHr     阈值心率（乳酸阈值心率）历史
 * @author gongxuesong
 * @date 2026-09-24
 */
public record SyncIngestRequest(List<DailyHealthDto> dailyHealth,
                                List<SleepRecordDto> sleep,
                                List<NapRecordDto> naps,
                                List<HrvRecordDto> hrv,
                                List<ActivityDto> activities,
                                List<TrainingStatusDto> trainingStatus,
                                List<FtpHistoryDto> ftpHistory,
                                Map<Long, List<ActivityHrZoneDto>> activityHrZones,
                                List<ThresholdHrDto> thresholdHr) {

    /**
     * 不含阈值心率的构造：阈值心率是后加的一类数据，老调用方（测试与只关心
     * 其它类型的上报）不必为此改一遍，缺失按空列表处理。
     */
    public SyncIngestRequest(List<DailyHealthDto> dailyHealth,
                             List<SleepRecordDto> sleep,
                             List<NapRecordDto> naps,
                             List<HrvRecordDto> hrv,
                             List<ActivityDto> activities,
                             List<TrainingStatusDto> trainingStatus,
                             List<FtpHistoryDto> ftpHistory,
                             Map<Long, List<ActivityHrZoneDto>> activityHrZones) {
        this(dailyHealth, sleep, naps, hrv, activities, trainingStatus, ftpHistory, activityHrZones, List.of());
    }
}
