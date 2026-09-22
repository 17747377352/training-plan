package com.trainingplan.platform.controller;

import com.trainingplan.platform.common.api.Result;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.training.AiUsageDto;
import com.trainingplan.platform.dto.training.GeneratedTrainingPlanDto;
import com.trainingplan.platform.service.AiUsageService;
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
    private final AiUsageService aiUsageService;

    /**
     * 生成当天的训练计划。
     *
     * <p>默认按数据指纹复用：指标没变时直接返回已存计划，不调用模型、不消耗配额。
     * 需要换一版时传 {@code force=true}。</p>
     *
     * @param force 是否强制重新调用模型
     * @param jwt   当前登录令牌
     * @return 生成结果（含当日用量）
     */
    @PostMapping("/generate")
    public Result<GeneratedTrainingPlanDto> generate(
            @RequestParam(defaultValue = "false") boolean force,
            @AuthenticationPrincipal Jwt jwt) {
        return Result.success(service.generate(currentUserId(jwt), force));
    }

    /**
     * 查询当日 AI 用量与剩余配额。
     *
     * @param jwt 当前登录令牌
     * @return 用量与配额
     */
    @GetMapping("/usage")
    public Result<AiUsageDto> usage(@AuthenticationPrincipal Jwt jwt) {
        return Result.success(aiUsageService.usage(currentUserId(jwt), null));
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
