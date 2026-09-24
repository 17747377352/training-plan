package com.trainingplan.platform.dto.profile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 个人中心「身体数据」聚合视图。
 *
 * <p>这些指标都是「最新值 + 变更历史」型（VO2max、FTP、阈值心率尤其如此），不是逐日数据，
 * 所以这里统一取各项的**最新一条**，并带上生效日期，避免让人误以为是当天的值。</p>
 *
 * @param ftp         最新骑行 FTP
 * @param training    最新训练状态里的能力指标
 * @param daily       最新每日健康
 * @param thresholdHr 阈值心率，按系列各一条（Garmin 目前只给跑步）
 * @param sleep       最近一次睡眠
 * @author gongxuesong
 * @date 2026-09-24
 */
public record ProfileOverviewDto(Ftp ftp,
                                 Training training,
                                 Daily daily,
                                 List<ThresholdHr> thresholdHr,
                                 Sleep sleep) {

    /** 骑行 FTP 及其生效日期；来源用于区分接口测得与由 NP/IF 反解。 */
    public record Ftp(Integer watts, LocalDate effectiveDate, String source) {
    }

    /**
     * 能力指标：VO2max 与身体年龄各自的**最新有效值**及其日期。
     *
     * <p>两者更新频率不同（身体年龄几乎天天有、VO2max 只在符合条件的骑行后才更新），
     * 所以各带一个日期，不能共用一条训练状态行的日期。</p>
     */
    public record Training(Double vo2maxValue, LocalDate vo2maxDate,
                           Integer fitnessAge, LocalDate fitnessAgeDate) {
    }

    /** 每日健康里的恢复类指标。 */
    public record Daily(Integer averageStressLevel, Integer bodyBatteryHighest, Integer bodyBatteryLowest,
                        Integer restingHeartRate, LocalDate calendarDate) {
    }

    /** 阈值心率：系列 + 数值 + 生效日期。 */
    public record ThresholdHr(String series, Integer heartRate, LocalDate effectiveDate) {
    }

    /** 最近一次睡眠；日期是这条睡眠归属的日期（通常是起床那天），起止时间为 GMT。 */
    public record Sleep(LocalDate calendarDate, Integer totalSeconds, Integer score,
                        Integer deepSeconds, Integer lightSeconds, Integer remSeconds, Integer awakeSeconds,
                        LocalDateTime startGmt, LocalDateTime endGmt) {
    }
}
