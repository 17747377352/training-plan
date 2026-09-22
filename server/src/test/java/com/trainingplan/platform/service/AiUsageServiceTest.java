package com.trainingplan.platform.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.trainingplan.platform.client.DeepSeekResult;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.config.DeepSeekProperties;
import com.trainingplan.platform.dto.training.AiUsageDto;
import com.trainingplan.platform.entity.AiGenerationLog;
import com.trainingplan.platform.mapper.AiGenerationLogMapper;
import com.trainingplan.platform.service.impl.AiUsageServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AI 配额与用量统计：失败也计费、复用不计费。 */
@ExtendWith(MockitoExtension.class)
class AiUsageServiceTest {

    private static final Long USER_ID = 7L;
    private static final LocalDate DAY = LocalDate.of(2026, 9, 21);

    @Mock
    private AiGenerationLogMapper logMapper;

    private AiUsageServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "ai-usage-test"),
                AiGenerationLog.class);
    }

    private AiUsageServiceImpl withLimit(int limit) {
        return new AiUsageServiceImpl(logMapper,
                new DeepSeekProperties("https://api.deepseek.com", "k", "deepseek-flash",
                        Duration.ofSeconds(90), limit));
    }

    @Test
    void countsSuccessAndFailureButNotReuse() {
        when(logMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                log("SUCCESS", 1000, 200, 1200),
                log("FAILED", null, null, null),
                log("REUSED", null, null, null),
                log("REUSED", null, null, null)));

        AiUsageDto usage = withLimit(5).usage(USER_ID, DAY);

        // 失败的那次仍然请求过模型，按实际调用计费；复用没有调用，不计
        assertThat(usage.usedToday()).isEqualTo(2);
        assertThat(usage.remainingToday()).isEqualTo(3);
        assertThat(usage.reusedToday()).isEqualTo(2);
        assertThat(usage.promptTokens()).isEqualTo(1000);
        assertThat(usage.completionTokens()).isEqualTo(200);
        assertThat(usage.totalTokens()).isEqualTo(1200);
    }

    @Test
    void quotaBlocksWhenLimitReached() {
        when(logMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                log("SUCCESS", null, null, null), log("FAILED", null, null, null)));

        assertThatThrownBy(() -> withLimit(2).requireQuota(USER_ID, DAY))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AI_QUOTA_EXCEEDED);
    }

    @Test
    void quotaAllowsWhenBelowLimit() {
        when(logMapper.selectList(any(Wrapper.class))).thenReturn(List.of(log("SUCCESS", null, null, null)));

        withLimit(3).requireQuota(USER_ID, DAY);
    }

    @Test
    void zeroLimitMeansUnlimitedAndSkipsQuery() {
        withLimit(0).requireQuota(USER_ID, DAY);

        // 0 表示不限制：连查询都不该发
        verify(logMapper, org.mockito.Mockito.never()).selectList(any(Wrapper.class));
    }

    @Test
    void recordsTokenUsageAndElapsedOnSuccess() {
        withLimit(5).recordSuccess(USER_ID, DAY, "cycling-plan-v2", "deepseek-flash",
                new DeepSeekResult("{}", 1000, 200, 1200, 2400L, 200));

        ArgumentCaptor<AiGenerationLog> captor = ArgumentCaptor.forClass(AiGenerationLog.class);
        verify(logMapper).insert(captor.capture());
        AiGenerationLog row = captor.getValue();
        assertThat(row.getOutcome()).isEqualTo("SUCCESS");
        assertThat(row.getTotalTokens()).isEqualTo(1200);
        assertThat(row.getElapsedMs()).isEqualTo(2400L);
        assertThat(row.getHttpStatus()).isEqualTo(200);
        assertThat(row.getModel()).isEqualTo("deepseek-flash");
        assertThat(row.getPromptVersion()).isEqualTo("cycling-plan-v2");
        assertThat(row.getCalendarDate()).isEqualTo(DAY);
    }

    @Test
    void recordsFailureWithErrorCodeOnly() {
        withLimit(5).recordFailure(USER_ID, DAY, "cycling-plan-v2", "deepseek-flash", "AI_TIMEOUT");

        ArgumentCaptor<AiGenerationLog> captor = ArgumentCaptor.forClass(AiGenerationLog.class);
        verify(logMapper).insert(captor.capture());
        AiGenerationLog row = captor.getValue();
        assertThat(row.getOutcome()).isEqualTo("FAILED");
        assertThat(row.getErrorCode()).isEqualTo("AI_TIMEOUT");
        // 只记错误码，不记模型原文或提示词，避免私人健康数据落库
        assertThat(row.getTotalTokens()).isNull();
        assertThat(row.getHttpStatus()).isNull();
    }

    @Test
    void reuseIsRecordedWithoutTokens() {
        withLimit(5).recordReuse(USER_ID, DAY, "cycling-plan-v2");

        ArgumentCaptor<AiGenerationLog> captor = ArgumentCaptor.forClass(AiGenerationLog.class);
        verify(logMapper).insert(captor.capture());
        assertThat(captor.getValue().getOutcome()).isEqualTo("REUSED");
        assertThat(captor.getValue().getTotalTokens()).isNull();
    }

    private AiGenerationLog log(String outcome, Integer prompt, Integer completion, Integer total) {
        AiGenerationLog row = new AiGenerationLog();
        row.setOutcome(outcome);
        row.setPromptTokens(prompt);
        row.setCompletionTokens(completion);
        row.setTotalTokens(total);
        return row;
    }
}
