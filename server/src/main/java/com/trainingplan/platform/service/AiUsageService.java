package com.trainingplan.platform.service;

import com.trainingplan.platform.client.DeepSeekResult;
import com.trainingplan.platform.dto.training.AiUsageDto;

import java.time.LocalDate;

/**
 * AI 生成用量与配额服务。
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
public interface AiUsageService {

    /**
     * 查询某天的用量与剩余配额。
     *
     * @param userId 当前登录用户 ID
     * @param date   归属日期，为空时取上海时区的今天
     * @return 用量与配额
     */
    AiUsageDto usage(Long userId, LocalDate date);

    /**
     * 校验配额，不足时抛 {@code AI_QUOTA_EXCEEDED}。
     *
     * @param userId 当前登录用户 ID
     * @param date   归属日期
     */
    void requireQuota(Long userId, LocalDate date);

    /**
     * 记一次成功调用。
     *
     * @param userId  当前登录用户 ID
     * @param date    归属日期
     * @param version 提示词版本
     * @param model   模型名
     * @param result  调用结果与用量
     */
    void recordSuccess(Long userId, LocalDate date, String version, String model, DeepSeekResult result);

    /**
     * 记一次失败调用（已消耗配额）。
     *
     * @param userId    当前登录用户 ID
     * @param date      归属日期
     * @param version   提示词版本
     * @param model     模型名
     * @param errorCode 错误码，不记录模型原文
     */
    void recordFailure(Long userId, LocalDate date, String version, String model, String errorCode);

    /**
     * 记一次指纹复用（未调用模型，不计入配额）。
     *
     * @param userId  当前登录用户 ID
     * @param date    归属日期
     * @param version 提示词版本
     */
    void recordReuse(Long userId, LocalDate date, String version);
}
