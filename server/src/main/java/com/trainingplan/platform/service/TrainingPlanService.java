package com.trainingplan.platform.service;

import com.trainingplan.platform.dto.training.GeneratedTrainingPlanDto;

import java.time.LocalDate;

/** 用户主动触发 DeepSeek 生成当天训练计划，不在读取页面时自动调用付费接口。 */
public interface TrainingPlanService {

    /**
     * 生成当天的训练计划并落库；同一天重复生成即覆盖。
     *
     * @param userId 当前登录用户 ID
     * @param force  true 表示忽略数据指纹强制重新调用模型（会消耗配额）
     * @return 生成结果
     */
    GeneratedTrainingPlanDto generate(Long userId, boolean force);

    /**
     * 读取已保存的计划，用于页面刷新后恢复，避免每次加载都调用付费接口。
     *
     * @param userId 当前登录用户 ID
     * @param date   归属日期，为空时取上海时区的今天
     * @return 已保存的计划，未生成过时返回 null
     */
    GeneratedTrainingPlanDto getPlan(Long userId, LocalDate date);
}
