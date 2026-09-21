package com.trainingplan.platform.service;

import com.trainingplan.platform.common.api.PageResult;
import com.trainingplan.platform.dto.activity.ActivityDetailDto;
import com.trainingplan.platform.dto.activity.ActivityQuery;
import com.trainingplan.platform.dto.activity.ActivityHrZoneViewDto;
import com.trainingplan.platform.dto.activity.ActivitySummaryDto;

import java.util.List;

/**
 * 当前用户的训练活动查询服务。
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
public interface ActivityService {

    /**
     * 分页查询当前用户的活动。
     *
     * @param userId 平台用户 ID
     * @param query  过滤与分页条件
     * @return 活动分页
     */
    PageResult<ActivitySummaryDto> listActivities(Long userId, ActivityQuery query);

    /**
     * 查询当前用户的单条活动详情。
     *
     * @param userId     平台用户 ID
     * @param activityId 活动主键
     * @return 活动全部已入库字段
     */
    ActivityDetailDto getActivity(Long userId, Long activityId);

    /**
     * 查询当前用户已入库的活动类型。
     *
     * @param userId 平台用户 ID
     * @return 按稳定标识排序的类型列表
     */
    List<String> listActivityTypes(Long userId);

    /**
     * 查询某次活动的心率区间分布。
     *
     * @param userId     当前登录用户 ID
     * @param activityId 活动主键
     * @return 按区间序号升序的心率区间
     */
    List<ActivityHrZoneViewDto> listHrZones(Long userId, Long activityId);
}
