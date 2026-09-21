package com.trainingplan.platform.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.health.DailyHealthTrendDto;
import com.trainingplan.platform.dto.health.HrvTrendDto;
import com.trainingplan.platform.dto.health.SleepTrendDto;
import com.trainingplan.platform.dto.health.TrendQuery;
import com.trainingplan.platform.entity.DailyHealth;
import com.trainingplan.platform.entity.GarminAccount;
import com.trainingplan.platform.entity.HrvRecord;
import com.trainingplan.platform.entity.NapRecord;
import com.trainingplan.platform.entity.SleepRecord;
import com.trainingplan.platform.mapper.DailyHealthMapper;
import com.trainingplan.platform.mapper.GarminAccountMapper;
import com.trainingplan.platform.mapper.HrvRecordMapper;
import com.trainingplan.platform.mapper.NapRecordMapper;
import com.trainingplan.platform.mapper.SleepRecordMapper;
import com.trainingplan.platform.service.HealthTrendService;
import com.trainingplan.platform.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 当前用户健康趋势查询实现。
 *
 * <p>所有查询先由平台用户解析其 Garmin 账号，再限定数据范围。
 * 请求参数不接收账号 ID，避免横向越权。</p>
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
@Service
@RequiredArgsConstructor
public class HealthTrendServiceImpl implements HealthTrendService {

    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_RANGE_DAYS = 366;

    private final DailyHealthMapper dailyHealthMapper;
    private final HrvRecordMapper hrvRecordMapper;
    private final NapRecordMapper napRecordMapper;
    private final SleepRecordMapper sleepRecordMapper;
    private final GarminAccountMapper garminAccountMapper;
    private final UserService userService;

    @Override
    public List<DailyHealthTrendDto> listDailyHealth(Long userId, TrendQuery query) {
        QueryContext context = resolveContext(userId, query);
        if (context.accountIds().isEmpty()) {
            return List.of();
        }
        return dailyHealthMapper.selectList(Wrappers.<DailyHealth>lambdaQuery()
                        .in(DailyHealth::getGarminAccountId, context.accountIds())
                        .between(DailyHealth::getCalendarDate, context.startDate(), context.endDate())
                        .orderByAsc(DailyHealth::getCalendarDate)
                        .orderByAsc(DailyHealth::getId))
                .stream()
                .map(this::toDailyHealthDto)
                .toList();
    }

    @Override
    public List<HrvTrendDto> listHrv(Long userId, TrendQuery query) {
        QueryContext context = resolveContext(userId, query);
        if (context.accountIds().isEmpty()) {
            return List.of();
        }
        return hrvRecordMapper.selectList(Wrappers.<HrvRecord>lambdaQuery()
                        .in(HrvRecord::getGarminAccountId, context.accountIds())
                        .between(HrvRecord::getCalendarDate, context.startDate(), context.endDate())
                        .orderByAsc(HrvRecord::getCalendarDate)
                        .orderByAsc(HrvRecord::getId))
                .stream()
                .map(this::toHrvDto)
                .toList();
    }

    @Override
    public List<SleepTrendDto> listSleep(Long userId, TrendQuery query) {
        QueryContext context = resolveContext(userId, query);
        if (context.accountIds().isEmpty()) {
            return List.of();
        }
        // 当日午睡合计：只用于展示，不并入 sleepTimeSeconds，也不参与判灯
        Map<LocalDate, int[]> napTotals = new HashMap<>();
        for (NapRecord nap : napRecordMapper.selectList(Wrappers.<NapRecord>lambdaQuery()
                .in(NapRecord::getGarminAccountId, context.accountIds())
                .between(NapRecord::getCalendarDate, context.startDate(), context.endDate()))) {
            int[] total = napTotals.computeIfAbsent(nap.getCalendarDate(), key -> new int[2]);
            total[0] += nap.getNapSeconds() == null ? 0 : nap.getNapSeconds();
            total[1] += 1;
        }
        return sleepRecordMapper.selectList(Wrappers.<SleepRecord>lambdaQuery()
                        .in(SleepRecord::getGarminAccountId, context.accountIds())
                        .between(SleepRecord::getCalendarDate, context.startDate(), context.endDate())
                        .orderByAsc(SleepRecord::getCalendarDate)
                        .orderByAsc(SleepRecord::getSleepStartGmt)
                        .orderByAsc(SleepRecord::getId))
                .stream()
                .map(row -> toSleepDto(row, napTotals.get(row.getCalendarDate())))
                .toList();
    }

    private QueryContext resolveContext(Long userId, TrendQuery query) {
        userService.getProfile(userId);
        LocalDate endDate = query.getEndDate() == null ? LocalDate.now() : query.getEndDate();
        LocalDate startDate = query.getStartDate() == null
                ? endDate.minusDays(DEFAULT_DAYS - 1L)
                : query.getStartDate();
        if (startDate.isAfter(endDate)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "开始日期不能晚于结束日期");
        }
        if (ChronoUnit.DAYS.between(startDate, endDate) >= MAX_RANGE_DAYS) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "单次最多查询366天数据");
        }
        List<Long> accountIds = garminAccountMapper.selectList(
                        Wrappers.<GarminAccount>lambdaQuery()
                                .select(GarminAccount::getId)
                                .eq(GarminAccount::getUserId, userId))
                .stream()
                .map(GarminAccount::getId)
                .toList();
        return new QueryContext(startDate, endDate, accountIds);
    }

    private DailyHealthTrendDto toDailyHealthDto(DailyHealth entity) {
        return new DailyHealthTrendDto(
                entity.getCalendarDate(),
                entity.getSteps(),
                entity.getDistanceMeters(),
                entity.getTotalKilocalories(),
                entity.getActiveKilocalories(),
                entity.getRestingHeartRate(),
                entity.getMinHeartRate(),
                entity.getMaxHeartRate(),
                entity.getAverageStressLevel(),
                entity.getBodyBatteryHighest(),
                entity.getBodyBatteryLowest());
    }

    private HrvTrendDto toHrvDto(HrvRecord entity) {
        return new HrvTrendDto(
                entity.getCalendarDate(),
                entity.getLastNightAvg(),
                entity.getWeeklyAvg(),
                entity.getHrvStatus(),
                entity.getBaselineLowUpper(),
                entity.getBaselineBalancedLow(),
                entity.getBaselineBalancedUpper());
    }

    private SleepTrendDto toSleepDto(SleepRecord entity, int[] nap) {
        return new SleepTrendDto(
                entity.getCalendarDate(),
                entity.getSleepStartGmt(),
                entity.getSleepEndGmt(),
                entity.getSleepTimeSeconds(),
                entity.getDeepSleepSeconds(),
                entity.getLightSleepSeconds(),
                entity.getRemSleepSeconds(),
                entity.getAwakeSleepSeconds(),
                entity.getSleepScore(),
                entity.getAvgSleepHrv(),
                entity.getAvgSpo2(),
                entity.getAvgRespiration(),
                nap == null ? 0 : nap[0],
                nap == null ? 0 : nap[1]);
    }

    private record QueryContext(LocalDate startDate, LocalDate endDate, List<Long> accountIds) {
    }
}
