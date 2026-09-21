package com.trainingplan.platform.service;

import com.trainingplan.platform.dto.training.TrainingGoalDto;
import com.trainingplan.platform.dto.training.TrainingGoalRequest;

/**
 * 用户训练目标服务。
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
public interface TrainingGoalService {

    /**
     * 读取当前用户的训练目标。
     *
     * @param userId 当前登录用户 ID
     * @return 目标，未设置时返回 null
     */
    TrainingGoalDto getGoal(Long userId);

    /**
     * 提交或更新训练目标；一个用户只保留一份。
     *
     * @param userId  当前登录用户 ID
     * @param request 目标内容
     * @return 保存后的目标
     */
    TrainingGoalDto saveGoal(Long userId, TrainingGoalRequest request);

    /**
     * 删除训练目标。
     *
     * @param userId 当前登录用户 ID
     */
    void deleteGoal(Long userId);
}
