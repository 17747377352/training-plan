package com.trainingplan.platform.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.trainingplan.platform.client.DeepSeekResult;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.config.DeepSeekProperties;
import com.trainingplan.platform.dto.training.AiUsageDto;
import com.trainingplan.platform.entity.AiGenerationLog;
import com.trainingplan.platform.mapper.AiGenerationLogMapper;
import com.trainingplan.platform.service.AiUsageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * AI 生成用量与配额实现。
 *
 * <p>配额按「真实调用次数」计：{@code SUCCESS} 与 {@code FAILED} 都消耗配额——
 * 失败的请求可能已经产生费用，按实际调用计数才诚实；{@code REUSED} 没有调用
 * 模型，不计入。</p>
 *
 * <p><strong>读写都必须用独立事务（REQUIRES_NEW）。</strong>调用方
 * {@code generate} 带 {@code @Transactional}，失败时会抛异常回滚；若日志写在
 * 同一事务里，失败记录会被一起回滚——配额少算、花费少报。这正是实测发现的：
 * 接口返回了 AI_INVALID_RESPONSE，而日志表一行都没有。
 * 读取同理：MySQL 默认 REPEATABLE READ，外层事务的快照看不到刚刚提交的记录，
 * 于是「生成成功」的响应里会显示"已用 0 次"。</p>
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
@Service
@RequiredArgsConstructor
public class AiUsageServiceImpl implements AiUsageService {

    /** 计入配额的结果类型。 */
    private static final List<String> BILLABLE = List.of("SUCCESS", "FAILED");
    private static final String OUTCOME_SUCCESS = "SUCCESS";
    private static final String OUTCOME_FAILED = "FAILED";
    private static final String OUTCOME_REUSED = "REUSED";

    private final AiGenerationLogMapper logMapper;
    private final DeepSeekProperties properties;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public AiUsageDto usage(Long userId, LocalDate date) {
        LocalDate day = date == null ? LocalDate.now(ZoneId.of("Asia/Shanghai")) : date;
        List<AiGenerationLog> rows = logMapper.selectList(Wrappers.<AiGenerationLog>lambdaQuery()
                .eq(AiGenerationLog::getUserId, userId)
                .eq(AiGenerationLog::getCalendarDate, day));
        long used = rows.stream().filter(row -> BILLABLE.contains(row.getOutcome())).count();
        long reused = rows.stream().filter(row -> OUTCOME_REUSED.equals(row.getOutcome())).count();
        long prompt = sum(rows, AiGenerationLog::getPromptTokens);
        long completion = sum(rows, AiGenerationLog::getCompletionTokens);
        long total = sum(rows, AiGenerationLog::getTotalTokens);
        int limit = properties.dailyLimit();
        long remaining = limit <= 0 ? Long.MAX_VALUE : Math.max(0, limit - used);
        return new AiUsageDto(day, limit, used, remaining, prompt, completion, total, reused);
    }

    @Override
    public void requireQuota(Long userId, LocalDate date) {
        int limit = properties.dailyLimit();
        if (limit <= 0) {
            return;
        }
        if (usage(userId, date).usedToday() >= limit) {
            throw new BusinessException(ErrorCode.AI_QUOTA_EXCEEDED);
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(Long userId, LocalDate date, String version, String model, DeepSeekResult result) {
        AiGenerationLog row = base(userId, date, version, OUTCOME_SUCCESS);
        row.setModel(model);
        row.setPromptTokens(result.promptTokens());
        row.setCompletionTokens(result.completionTokens());
        row.setTotalTokens(result.totalTokens());
        row.setElapsedMs(result.elapsedMillis());
        row.setHttpStatus(result.httpStatus());
        logMapper.insert(row);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long userId, LocalDate date, String version, String model, String errorCode) {
        AiGenerationLog row = base(userId, date, version, OUTCOME_FAILED);
        row.setModel(model);
        row.setErrorCode(errorCode);
        logMapper.insert(row);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordReuse(Long userId, LocalDate date, String version) {
        logMapper.insert(base(userId, date, version, OUTCOME_REUSED));
    }

    /**
     * 构造日志公共字段。
     *
     * @param userId  用户 ID
     * @param date    归属日期
     * @param version 提示词版本
     * @param outcome 结果类型
     * @return 待插入的日志
     */
    private AiGenerationLog base(Long userId, LocalDate date, String version, String outcome) {
        AiGenerationLog row = new AiGenerationLog();
        row.setUserId(userId);
        row.setCalendarDate(date);
        row.setRequestedAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        row.setPromptVersion(version);
        row.setOutcome(outcome);
        return row;
    }

    private long sum(List<AiGenerationLog> rows, java.util.function.Function<AiGenerationLog, Integer> field) {
        return rows.stream().map(field).filter(java.util.Objects::nonNull).mapToLong(Integer::longValue).sum();
    }
}
