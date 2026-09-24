package com.trainingplan.platform.service.impl;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.trainingplan.platform.dto.profile.ProfileOverviewDto;
import com.trainingplan.platform.entity.DailyHealth;
import com.trainingplan.platform.entity.FtpHistory;
import com.trainingplan.platform.entity.GarminAccount;
import com.trainingplan.platform.entity.SleepRecord;
import com.trainingplan.platform.entity.ThresholdHr;
import com.trainingplan.platform.entity.TrainingStatus;
import com.trainingplan.platform.mapper.DailyHealthMapper;
import com.trainingplan.platform.mapper.FtpHistoryMapper;
import com.trainingplan.platform.mapper.GarminAccountMapper;
import com.trainingplan.platform.mapper.SleepRecordMapper;
import com.trainingplan.platform.mapper.ThresholdHrMapper;
import com.trainingplan.platform.mapper.TrainingStatusMapper;
import com.trainingplan.platform.service.ProfileOverviewService;
import com.trainingplan.platform.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 个人中心身体数据实现。
 *
 * <p>这些指标多为「最新值 + 变更历史」型，所以统一按日期倒序取最新一条，并保留生效日期。
 * 数据范围沿用其它查询接口的做法：先按当前用户取出其所有 Garmin 账号，再用账号集合收窄，
 * 不能只按用户 ID 查（数据表只有账号维度）。</p>
 *
 * @author gongxuesong
 * @date 2026-09-24
 */
@Service
@RequiredArgsConstructor
public class ProfileOverviewServiceImpl implements ProfileOverviewService {

    private final UserService userService;
    private final GarminAccountMapper accountMapper;
    private final DailyHealthMapper dailyHealthMapper;
    private final TrainingStatusMapper trainingStatusMapper;
    private final FtpHistoryMapper ftpHistoryMapper;
    private final ThresholdHrMapper thresholdHrMapper;
    private final SleepRecordMapper sleepRecordMapper;

    @Override
    public ProfileOverviewDto overview(Long userId) {
        userService.getProfile(userId);
        List<Long> accountIds = accountMapper.selectList(Wrappers.<GarminAccount>lambdaQuery()
                        .select(GarminAccount::getId)
                        .eq(GarminAccount::getUserId, userId))
                .stream()
                .map(GarminAccount::getId)
                .toList();
        if (accountIds.isEmpty()) {
            return new ProfileOverviewDto(null, null, null, List.of(), null);
        }

        LocalDate today = LocalDate.now();
        FtpHistory ftp = latestValue(ftpHistoryMapper, accountIds, FtpHistory::getGarminAccountId,
                FtpHistory::getEffectiveDate, FtpHistory::getFtpWatts,
                wrapper -> wrapper.le(FtpHistory::getEffectiveDate, today));
        // VO2max 与身体年龄的更新频率不同，各取自己的最新非空值，避免最新那行恰好没值就显示成空
        TrainingStatus vo2maxRow = latestValue(trainingStatusMapper, accountIds,
                TrainingStatus::getGarminAccountId, TrainingStatus::getCalendarDate,
                TrainingStatus::getVo2maxValue, null);
        TrainingStatus fitnessRow = latestValue(trainingStatusMapper, accountIds,
                TrainingStatus::getGarminAccountId, TrainingStatus::getCalendarDate,
                TrainingStatus::getFitnessAge, null);
        DailyHealth daily = latestByDate(dailyHealthMapper, accountIds, DailyHealth::getGarminAccountId,
                DailyHealth::getCalendarDate);
        SleepRecord sleep = latestByDate(sleepRecordMapper, accountIds, SleepRecord::getGarminAccountId,
                SleepRecord::getCalendarDate);

        return new ProfileOverviewDto(
                ftp == null ? null : new ProfileOverviewDto.Ftp(ftp.getFtpWatts(), ftp.getEffectiveDate(), ftp.getSource()),
                (vo2maxRow == null && fitnessRow == null) ? null : new ProfileOverviewDto.Training(
                        vo2maxRow == null ? null : vo2maxRow.getVo2maxValue(),
                        vo2maxRow == null ? null : vo2maxRow.getCalendarDate(),
                        fitnessRow == null ? null : fitnessRow.getFitnessAge(),
                        fitnessRow == null ? null : fitnessRow.getCalendarDate()),
                daily == null ? null : new ProfileOverviewDto.Daily(daily.getAverageStressLevel(),
                        daily.getBodyBatteryHighest(), daily.getBodyBatteryLowest(),
                        daily.getRestingHeartRate(), daily.getCalendarDate()),
                thresholdHr(accountIds),
                sleep == null ? null : new ProfileOverviewDto.Sleep(sleep.getCalendarDate(),
                        sleep.getSleepTimeSeconds(), sleep.getSleepScore(), sleep.getDeepSleepSeconds(),
                        sleep.getLightSleepSeconds(), sleep.getRemSleepSeconds(), sleep.getAwakeSleepSeconds(),
                        sleep.getSleepStartGmt(), sleep.getSleepEndGmt()));
    }

    /**
     * 取每个系列的最新一条阈值心率。
     *
     * <p>按系列分组而不是只取全局最新一条：Garmin 目前只提供跑步系列，将来若补上骑行系列，
     * 两个数值会互相遮盖。</p>
     */
    private List<ProfileOverviewDto.ThresholdHr> thresholdHr(List<Long> accountIds) {
        List<ThresholdHr> rows = thresholdHrMapper.selectList(Wrappers.<ThresholdHr>lambdaQuery()
                .in(ThresholdHr::getGarminAccountId, accountIds)
                .orderByDesc(ThresholdHr::getEffectiveDate)
                .orderByDesc(ThresholdHr::getId));
        Map<String, ThresholdHr> bySeries = new LinkedHashMap<>();
        for (ThresholdHr row : rows) {
            bySeries.putIfAbsent(row.getSeries(), row);
        }
        return bySeries.values().stream()
                .map(row -> new ProfileOverviewDto.ThresholdHr(row.getSeries(), row.getHeartRate(),
                        row.getEffectiveDate()))
                .toList();
    }

    /** 按日期倒序取最新一条（用于每日都有数据的类型，例如每日健康与睡眠）。 */
    private <T> T latestByDate(BaseMapper<T> mapper, List<Long> accountIds,
                               SFunction<T, ?> accountColumn, SFunction<T, ?> dateColumn) {
        return mapper.selectOne(Wrappers.<T>lambdaQuery()
                .in(accountColumn, accountIds)
                .orderByDesc(dateColumn)
                .last("LIMIT 1"));
    }

    /**
     * 取「该字段最近一次有值」的那条记录。
     *
     * <p>这些指标是稀疏的（VO2max 只在符合条件的骑行后才更新、FTP 是变更历史），
     * 直接取最新一行很容易遇到「那行恰好没有这个字段」而显示成空 —— 那不是「没有数据」，
     * 只是那天没更新。账号列与日期/取值列都必须用真实方法引用传入，SFunction 靠方法名解析列名。</p>
     */
    private <T> T latestValue(BaseMapper<T> mapper, List<Long> accountIds,
                              SFunction<T, ?> accountColumn, SFunction<T, ?> dateColumn,
                              SFunction<T, ?> valueColumn, Consumer<LambdaQueryWrapper<T>> extra) {
        LambdaQueryWrapper<T> wrapper = Wrappers.<T>lambdaQuery()
                .in(accountColumn, accountIds)
                .isNotNull(valueColumn)
                .orderByDesc(dateColumn)
                .last("LIMIT 1");
        if (extra != null) {
            extra.accept(wrapper);
        }
        return mapper.selectOne(wrapper);
    }
}
