package com.trainingplan.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.trainingplan.platform.common.api.PageResult;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.activity.ActivityQuery;
import com.trainingplan.platform.dto.activity.ActivitySummaryDto;
import com.trainingplan.platform.entity.Activity;
import com.trainingplan.platform.entity.GarminAccount;
import com.trainingplan.platform.mapper.ActivityMapper;
import com.trainingplan.platform.mapper.GarminAccountMapper;
import com.trainingplan.platform.service.ActivityService;
import com.trainingplan.platform.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户活动查询实现。
 *
 * <p>查询前先按平台用户筛选 Garmin 账号 ID，后续所有活动查询都强制
 * 限定在这些账号内，请求参数不接收账号 ID，避免越权查询。</p>
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
@Service
@RequiredArgsConstructor
public class ActivityServiceImpl implements ActivityService {

    private final ActivityMapper activityMapper;
    private final GarminAccountMapper garminAccountMapper;
    private final UserService userService;

    @Override
    public PageResult<ActivitySummaryDto> listActivities(Long userId, ActivityQuery query) {
        validateDateRange(query);
        userService.getProfile(userId);
        List<Long> accountIds = findAccountIds(userId);
        if (accountIds.isEmpty()) {
            return new PageResult<>(0L, query.currentPage(), query.pageSize(), List.of());
        }

        String keyword = trimToNull(query.getKeyword());
        String typeKey = trimToNull(query.getTypeKey());
        LambdaQueryWrapper<Activity> wrapper = Wrappers.<Activity>lambdaQuery()
                .in(Activity::getGarminAccountId, accountIds)
                .ge(query.getStartDate() != null, Activity::getStartTimeLocal,
                        atStartOfDay(query.getStartDate()))
                .lt(query.getEndDate() != null, Activity::getStartTimeLocal,
                        query.getEndDate() == null ? null : atStartOfDay(query.getEndDate().plusDays(1)))
                .eq(StringUtils.hasText(typeKey), Activity::getActivityTypeKey, typeKey)
                .like(StringUtils.hasText(keyword), Activity::getActivityName, keyword)
                .orderByDesc(Activity::getStartTimeLocal)
                .orderByDesc(Activity::getId);

        IPage<Activity> page = activityMapper.selectPage(
                Page.of(query.currentPage(), query.pageSize()), wrapper);
        List<ActivitySummaryDto> records = page.getRecords().stream()
                .map(this::toSummary)
                .toList();
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), records);
    }

    @Override
    public List<String> listActivityTypes(Long userId) {
        userService.getProfile(userId);
        List<Long> accountIds = findAccountIds(userId);
        if (accountIds.isEmpty()) {
            return List.of();
        }
        return activityMapper.selectList(Wrappers.<Activity>lambdaQuery()
                        .select(Activity::getActivityTypeKey)
                        .in(Activity::getGarminAccountId, accountIds)
                        .isNotNull(Activity::getActivityTypeKey)
                        .groupBy(Activity::getActivityTypeKey)
                        .orderByAsc(Activity::getActivityTypeKey))
                .stream()
                .map(Activity::getActivityTypeKey)
                .filter(StringUtils::hasText)
                .toList();
    }

    private List<Long> findAccountIds(Long userId) {
        return garminAccountMapper.selectList(Wrappers.<GarminAccount>lambdaQuery()
                        .select(GarminAccount::getId)
                        .eq(GarminAccount::getUserId, userId))
                .stream()
                .map(GarminAccount::getId)
                .toList();
    }

    private void validateDateRange(ActivityQuery query) {
        if (query.getStartDate() != null && query.getEndDate() != null
                && query.getStartDate().isAfter(query.getEndDate())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "开始日期不能晚于结束日期");
        }
    }

    private LocalDateTime atStartOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay();
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private ActivitySummaryDto toSummary(Activity activity) {
        LocalDateTime startTime = activity.getStartTimeLocal() == null
                ? activity.getStartTimeGmt()
                : activity.getStartTimeLocal();
        return new ActivitySummaryDto(
                activity.getId(),
                activity.getActivityTypeKey(),
                activity.getActivityName(),
                startTime,
                activity.getDurationSeconds(),
                activity.getMovingDurationSeconds(),
                activity.getDistanceMeters(),
                activity.getElevationGain(),
                activity.getAverageSpeed(),
                activity.getMaxSpeed(),
                activity.getAverageHr(),
                activity.getMaxHr(),
                activity.getCalories(),
                activity.getAvgPower(),
                activity.getNormPower(),
                activity.getMax20minPower(),
                activity.getIntensityFactor(),
                activity.getTrainingStressScore(),
                activity.getAvgCadence(),
                activity.getAvgLeftBalance(),
                activity.getAerobicTrainingEffect(),
                activity.getAnaerobicTrainingEffect(),
                activity.getTrainingEffectLabel(),
                activity.getActivityTrainingLoad(),
                activity.getVo2maxValue());
    }
}
