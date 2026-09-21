package com.trainingplan.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.trainingplan.platform.common.api.PageResult;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.activity.ActivityDetailDto;
import com.trainingplan.platform.dto.activity.ActivityHrZoneViewDto;
import com.trainingplan.platform.dto.activity.ActivityQuery;
import com.trainingplan.platform.dto.activity.ActivitySummaryDto;
import com.trainingplan.platform.entity.Activity;
import com.trainingplan.platform.entity.ActivityHrZone;
import com.trainingplan.platform.entity.GarminAccount;
import com.trainingplan.platform.mapper.ActivityHrZoneMapper;
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
    private final ActivityHrZoneMapper activityHrZoneMapper;
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
    public ActivityDetailDto getActivity(Long userId, Long activityId) {
        userService.getProfile(userId);
        List<Long> accountIds = findAccountIds(userId);
        if (accountIds.isEmpty()) {
            throw activityNotFound();
        }

        Activity activity = activityMapper.selectOne(Wrappers.<Activity>lambdaQuery()
                .eq(Activity::getId, activityId)
                .in(Activity::getGarminAccountId, accountIds));
        if (activity == null) {
            throw activityNotFound();
        }
        return toDetail(activity);
    }

    @Override
    public List<ActivityHrZoneViewDto> listHrZones(Long userId, Long activityId) {
        // 先按归属取活动，取不到就与「活动不存在」同样处理，不泄露别人活动的存在
        Activity activity = ownedActivity(userId, activityId);
        return activityHrZoneMapper.selectList(Wrappers.<ActivityHrZone>lambdaQuery()
                        .eq(ActivityHrZone::getActivityId, activity.getId())
                        .orderByAsc(ActivityHrZone::getZoneNumber))
                .stream()
                .map(zone -> new ActivityHrZoneViewDto(
                        zone.getZoneNumber(), zone.getZoneLowBoundary(), zone.getSecondsInZone()))
                .toList();
    }

    /**
     * 取当前用户名下的活动，找不到即抛 404。
     *
     * @param userId     当前登录用户 ID
     * @param activityId 活动主键
     * @return 活动实体
     */
    private Activity ownedActivity(Long userId, Long activityId) {
        userService.getProfile(userId);
        List<Long> accountIds = findAccountIds(userId);
        if (accountIds.isEmpty()) {
            throw activityNotFound();
        }
        Activity activity = activityMapper.selectOne(Wrappers.<Activity>lambdaQuery()
                .eq(Activity::getId, activityId)
                .in(Activity::getGarminAccountId, accountIds));
        if (activity == null) {
            throw activityNotFound();
        }
        return activity;
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

    private ActivityDetailDto toDetail(Activity activity) {
        return new ActivityDetailDto(
                activity.getId(),
                activity.getGarminAccountId(),
                idToString(activity.getGarminActivityId()),
                activity.getActivityTypeKey(),
                activity.getActivityTypeId(),
                activity.getParentTypeId(),
                activity.getActivityName(),
                activity.getStartTimeGmt(),
                activity.getStartTimeLocal(),
                activity.getDurationSeconds(),
                activity.getMovingDurationSeconds(),
                activity.getElapsedDurationSeconds(),
                activity.getDistanceMeters(),
                activity.getElevationGain(),
                activity.getElevationLoss(),
                activity.getAvgElevation(),
                activity.getMaxElevation(),
                activity.getMinElevation(),
                activity.getAverageSpeed(),
                activity.getMaxSpeed(),
                activity.getAverageHr(),
                activity.getMaxHr(),
                activity.getCalories(),
                activity.getBmrCalories(),
                activity.getAvgPower(),
                activity.getMaxPower(),
                activity.getNormPower(),
                activity.getMax20minPower(),
                activity.getIntensityFactor(),
                activity.getTrainingStressScore(),
                activity.getAvgCadence(),
                activity.getMaxCadence(),
                activity.getAvgLeftBalance(),
                activity.getAerobicTrainingEffect(),
                activity.getAnaerobicTrainingEffect(),
                activity.getTrainingEffectLabel(),
                activity.getActivityTrainingLoad(),
                activity.getPowerZone1Seconds(),
                activity.getPowerZone2Seconds(),
                activity.getPowerZone3Seconds(),
                activity.getPowerZone4Seconds(),
                activity.getPowerZone5Seconds(),
                activity.getPowerZone6Seconds(),
                activity.getPowerZone7Seconds(),
                activity.getLapCount(),
                activity.getStrokes(),
                activity.getAvgRespirationRate(),
                activity.getMinTemperature(),
                activity.getMaxTemperature(),
                activity.getVo2maxValue(),
                idToString(activity.getDeviceId()),
                activity.getCreateTime(),
                activity.getUpdateTime());
    }

    private BusinessException activityNotFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, "活动不存在");
    }

    private String idToString(Long value) {
        return value == null ? null : value.toString();
    }
}
