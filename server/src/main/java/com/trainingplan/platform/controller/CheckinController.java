package com.trainingplan.platform.controller;

import com.trainingplan.platform.common.api.Result;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.checkin.CheckinQuery;
import com.trainingplan.platform.dto.checkin.CheckinRequest;
import com.trainingplan.platform.dto.checkin.DailyCheckinDto;
import com.trainingplan.platform.service.CheckinService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 当前登录用户的每日手工打卡接口。
 *
 * <p>记录一律挂在 JWT 的用户身份下，请求参数不接收用户 ID。</p>
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
@RestController
@RequestMapping("/api/checkins")
@RequiredArgsConstructor
public class CheckinController {

    private final CheckinService checkinService;

    /**
     * 查询日期区间内的打卡记录。
     *
     * @param query 日期区间
     * @param jwt   当前登录令牌
     * @return 按日期升序的打卡记录
     */
    @GetMapping
    public Result<List<DailyCheckinDto>> listCheckins(
            @Valid CheckinQuery query,
            @AuthenticationPrincipal Jwt jwt) {
        return Result.success(checkinService.listCheckins(currentUserId(jwt), query));
    }

    /**
     * 提交或更新某一天的打卡。
     *
     * @param calendarDate 归属日期
     * @param request      打卡内容
     * @param jwt          当前登录令牌
     * @return 保存后的打卡记录
     */
    @PutMapping("/{calendarDate}")
    public Result<DailyCheckinDto> saveCheckin(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate calendarDate,
            @Valid @RequestBody CheckinRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return Result.success(checkinService.saveCheckin(currentUserId(jwt), calendarDate, request));
    }

    /**
     * 删除某一天的打卡。
     *
     * @param calendarDate 归属日期
     * @param jwt          当前登录令牌
     * @return 空响应
     */
    @DeleteMapping("/{calendarDate}")
    public Result<Void> deleteCheckin(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate calendarDate,
            @AuthenticationPrincipal Jwt jwt) {
        checkinService.deleteCheckin(currentUserId(jwt), calendarDate);
        return Result.success(null);
    }

    private Long currentUserId(Jwt jwt) {
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
