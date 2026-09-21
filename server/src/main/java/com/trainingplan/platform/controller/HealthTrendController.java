package com.trainingplan.platform.controller;

import com.trainingplan.platform.common.api.Result;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.health.DailyHealthTrendDto;
import com.trainingplan.platform.dto.health.HrvTrendDto;
import com.trainingplan.platform.dto.health.SleepTrendDto;
import com.trainingplan.platform.dto.health.TrendQuery;
import com.trainingplan.platform.service.HealthTrendService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 当前登录用户的健康趋势接口。 */
@RestController
@RequiredArgsConstructor
public class HealthTrendController {

    private final HealthTrendService healthTrendService;

    @GetMapping("/api/health/daily")
    public Result<List<DailyHealthTrendDto>> listDailyHealth(
            @Valid TrendQuery query,
            @AuthenticationPrincipal Jwt jwt) {
        return Result.success(healthTrendService.listDailyHealth(currentUserId(jwt), query));
    }

    @GetMapping("/api/health/hrv")
    public Result<List<HrvTrendDto>> listHrv(
            @Valid TrendQuery query,
            @AuthenticationPrincipal Jwt jwt) {
        return Result.success(healthTrendService.listHrv(currentUserId(jwt), query));
    }

    @GetMapping("/api/sleep")
    public Result<List<SleepTrendDto>> listSleep(
            @Valid TrendQuery query,
            @AuthenticationPrincipal Jwt jwt) {
        return Result.success(healthTrendService.listSleep(currentUserId(jwt), query));
    }

    private Long currentUserId(Jwt jwt) {
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
