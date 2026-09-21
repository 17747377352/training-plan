package com.trainingplan.platform.controller;

import com.trainingplan.platform.common.api.PageResult;
import com.trainingplan.platform.common.api.Result;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.activity.ActivityDetailDto;
import com.trainingplan.platform.dto.activity.ActivityQuery;
import com.trainingplan.platform.dto.activity.ActivitySummaryDto;
import com.trainingplan.platform.service.ActivityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 当前登录用户的训练活动查询接口。
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
@RestController
@RequestMapping("/api/activities")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityService activityService;

    /**
     * 分页查询当前用户的活动。
     *
     * @param query 过滤与分页条件
     * @param jwt   当前登录令牌
     * @return 活动分页
     */
    @GetMapping
    public Result<PageResult<ActivitySummaryDto>> listActivities(
            @Valid ActivityQuery query,
            @AuthenticationPrincipal Jwt jwt) {
        return Result.success(activityService.listActivities(currentUserId(jwt), query));
    }

    /**
     * 查询当前用户的活动详情。
     *
     * @param id  活动主键
     * @param jwt 当前登录令牌
     * @return 活动全部已入库字段
     */
    @GetMapping("/{id}")
    public Result<ActivityDetailDto> getActivity(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt) {
        return Result.success(activityService.getActivity(currentUserId(jwt), id));
    }

    /**
     * 查询当前用户数据中已有的活动类型。
     *
     * @param jwt 当前登录令牌
     * @return 活动类型稳定标识列表
     */
    @GetMapping("/types")
    public Result<List<String>> listActivityTypes(@AuthenticationPrincipal Jwt jwt) {
        return Result.success(activityService.listActivityTypes(currentUserId(jwt)));
    }

    private Long currentUserId(Jwt jwt) {
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
