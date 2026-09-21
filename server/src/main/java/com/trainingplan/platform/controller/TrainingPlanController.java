package com.trainingplan.platform.controller;

import com.trainingplan.platform.common.api.Result;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.training.GeneratedTrainingPlanDto;
import com.trainingplan.platform.service.TrainingPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/** 显式生成操作才调用 DeepSeek；不接收客户端传来的健康数据或账号 ID。 */
@RestController
@RequestMapping("/api/training-plans")
@RequiredArgsConstructor
public class TrainingPlanController {
    private final TrainingPlanService service;

    @PostMapping("/generate")
    public Result<GeneratedTrainingPlanDto> generate(@AuthenticationPrincipal Jwt jwt) {
        return Result.success(service.generate(currentUserId(jwt)));
    }

    /**
     * 读取已保存的计划，供页面刷新后恢复。
     *
     * <p>未生成过时 data 为 null，前端据此显示「今天还没有计划」，
     * 而不是把它当成错误。</p>
     *
     * @param date 归属日期，为空取今天
     * @param jwt  当前登录令牌
     * @return 已保存的计划，未生成过时为 null
     */
    @GetMapping
    public Result<GeneratedTrainingPlanDto> getPlan(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal Jwt jwt) {
        return Result.success(service.getPlan(currentUserId(jwt), date));
    }

    private Long currentUserId(Jwt jwt) {
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
