package com.trainingplan.platform.dto.training;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 模型生成结果与其使用的数据摘要；功率瓦数由服务端确定性换算。
 *
 * @param reused 数据指纹未变，直接返回已存计划而没有调用模型
 * @param usage  生成后的当日用量与剩余配额
 */
public record GeneratedTrainingPlanDto(LocalDate calendarDate, Instant generatedAt,
        String provider, String model, String promptVersion, String dataFingerprint,
        String dataSummary, TrainingAdviceDto.Light light, String rationale,
        TrainingAdviceDto.Prescription prescription,
        boolean reused, AiUsageDto usage) {}
