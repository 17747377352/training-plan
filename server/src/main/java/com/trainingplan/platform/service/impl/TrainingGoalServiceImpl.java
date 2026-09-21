package com.trainingplan.platform.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.training.TrainingGoalDto;
import com.trainingplan.platform.dto.training.TrainingGoalRequest;
import com.trainingplan.platform.entity.TrainingGoal;
import com.trainingplan.platform.mapper.TrainingGoalMapper;
import com.trainingplan.platform.service.TrainingGoalService;
import com.trainingplan.platform.service.UserService;
import com.trainingplan.platform.service.training.TrainingGoalType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 训练目标实现。
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
@Service
@RequiredArgsConstructor
public class TrainingGoalServiceImpl implements TrainingGoalService {

    private final TrainingGoalMapper trainingGoalMapper;
    private final UserService userService;

    @Override
    public TrainingGoalDto getGoal(Long userId) {
        userService.getProfile(userId);
        TrainingGoal row = find(userId);
        return row == null ? null : toDto(row);
    }

    @Override
    public TrainingGoalDto saveGoal(Long userId, TrainingGoalRequest request) {
        userService.getProfile(userId);
        if (TrainingGoalType.fromCode(request.getGoalType()) == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "目标类型不合法");
        }
        if (request.getTargetDate() != null
                && request.getTargetDate().isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai")))) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "目标日期不能早于今天");
        }
        String description = StringUtils.hasText(request.getDescription())
                ? request.getDescription().trim() : null;

        TrainingGoal existing = find(userId);
        TrainingGoal entity = existing == null ? new TrainingGoal() : existing;
        entity.setUserId(userId);
        entity.setGoalType(request.getGoalType());
        entity.setTargetDate(request.getTargetDate());
        entity.setWeeklySessions(request.getWeeklySessions());
        entity.setWeeklyMinutes(request.getWeeklyMinutes());
        entity.setDescription(description);
        if (existing == null) {
            trainingGoalMapper.insert(entity);
        } else {
            trainingGoalMapper.updateById(entity);
        }
        return toDto(entity);
    }

    @Override
    public void deleteGoal(Long userId) {
        userService.getProfile(userId);
        trainingGoalMapper.delete(Wrappers.<TrainingGoal>lambdaQuery()
                .eq(TrainingGoal::getUserId, userId));
    }

    /**
     * 取当前用户的目标。
     *
     * @param userId 平台用户 ID
     * @return 目标实体，未设置时为 null
     */
    private TrainingGoal find(Long userId) {
        return trainingGoalMapper.selectOne(Wrappers.<TrainingGoal>lambdaQuery()
                .eq(TrainingGoal::getUserId, userId));
    }

    private TrainingGoalDto toDto(TrainingGoal row) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        Long days = row.getTargetDate() == null ? null
                : ChronoUnit.DAYS.between(today, row.getTargetDate());
        return new TrainingGoalDto(row.getGoalType(),
                TrainingGoalType.labelOf(row.getGoalType()),
                row.getTargetDate(), days, row.getWeeklySessions(), row.getWeeklyMinutes(),
                row.getDescription());
    }
}
