package com.trainingplan.platform.controller;

import com.trainingplan.platform.common.api.Result;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.training.TrainingGoalDto;
import com.trainingplan.platform.dto.training.TrainingGoalRequest;
import com.trainingplan.platform.service.TrainingGoalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前登录用户的训练目标接口。
 *
 * <p>目标一律挂在 JWT 的用户身份下，请求参数不接收用户 ID。</p>
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
@RestController
@RequestMapping("/api/training-goals")
@RequiredArgsConstructor
public class TrainingGoalController {

    private final TrainingGoalService trainingGoalService;

    /**
     * 读取训练目标。
     *
     * @param jwt 当前登录令牌
     * @return 目标，未设置时为 null
     */
    @GetMapping
    public Result<TrainingGoalDto> getGoal(@AuthenticationPrincipal Jwt jwt) {
        return Result.success(trainingGoalService.getGoal(currentUserId(jwt)));
    }

    /**
     * 提交或更新训练目标。
     *
     * @param request 目标内容
     * @param jwt     当前登录令牌
     * @return 保存后的目标
     */
    @PutMapping
    public Result<TrainingGoalDto> saveGoal(@Valid @RequestBody TrainingGoalRequest request,
                                           @AuthenticationPrincipal Jwt jwt) {
        return Result.success(trainingGoalService.saveGoal(currentUserId(jwt), request));
    }

    /**
     * 删除训练目标。
     *
     * @param jwt 当前登录令牌
     * @return 空响应
     */
    @DeleteMapping
    public Result<Void> deleteGoal(@AuthenticationPrincipal Jwt jwt) {
        trainingGoalService.deleteGoal(currentUserId(jwt));
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
