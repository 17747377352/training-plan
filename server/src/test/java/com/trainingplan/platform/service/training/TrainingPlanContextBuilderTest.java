package com.trainingplan.platform.service.training;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainingplan.platform.entity.*;
import com.trainingplan.platform.mapper.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 真实序列化上下文：各项指标齐全、账号一致、有界查询、私人标识不进入提示词。 */
@ExtendWith(MockitoExtension.class)
class TrainingPlanContextBuilderTest {
    @Mock private TrainingStatusMapper training;
    @Mock private HrvRecordMapper hrv;
    @Mock private SleepRecordMapper sleep;
    @Mock private DailyHealthMapper health;
    @Mock private FtpHistoryMapper ftp;
    @Mock private ActivityMapper activities;
    @Mock private DailyCheckinMapper checkins;
    private TrainingPlanContextBuilder builder;
    private final LocalDate day = LocalDate.of(2026, 9, 21);

    @BeforeEach
    void setUp() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "model-context-test");
        for (Class<?> type : List.of(TrainingStatus.class, HrvRecord.class, SleepRecord.class,
                DailyHealth.class, FtpHistory.class, Activity.class, DailyCheckin.class)) TableInfoHelper.initTableInfo(assistant, type);
        builder = new TrainingPlanContextBuilder(new ObjectMapper().findAndRegisterModules(), training, hrv, sleep, health, ftp, activities, checkins);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void includesMetricsButExcludesIdentityAndScopesEveryHistoryQuery() {
        var trainingRow = new TrainingStatus(); trainingRow.setAcwrRatio(1.2); trainingRow.setId(999L);
        var hrvRow = new HrvRecord(); hrvRow.setLastNightAvg(61.0); hrvRow.setGarminAccountId(11L);
        var sleepRow = new SleepRecord(); sleepRow.setSleepTimeSeconds(28800);
        var healthRow = new DailyHealth(); healthRow.setRestingHeartRate(48);
        var ftpRow = new FtpHistory(); ftpRow.setFtpWatts(213); ftpRow.setEffectiveDate(day.minusDays(20));
        var activity = new Activity(); activity.setActivityName("private location and name"); activity.setTrainingStressScore(60.0);
        activity.setGarminActivityId(888L); activity.setDeviceId(999L);
        var checkin = new DailyCheckin(); checkin.setRpe(3); checkin.setWeightKg(new BigDecimal("71")); checkin.setUserId(7L);
        when(training.selectList(any(Wrapper.class))).thenReturn(List.of(trainingRow));
        when(hrv.selectList(any(Wrapper.class))).thenReturn(List.of(hrvRow));
        when(sleep.selectList(any(Wrapper.class))).thenReturn(List.of(sleepRow));
        when(health.selectList(any(Wrapper.class))).thenReturn(List.of(healthRow));
        when(ftp.selectList(any(Wrapper.class))).thenReturn(List.of(ftpRow));
        when(activities.selectList(any(Wrapper.class))).thenReturn(List.of(activity));
        when(checkins.selectList(any(Wrapper.class))).thenReturn(List.of(checkin));
        var advice = new TrainingAdviceEngine().evaluate(day, 11L, "source", null, List.of(), List.of(), null, List.of());
        var result = builder.build(7L, advice);
        var payload = result.payload();
        assertThat(payload.path("training").get(0).path("acwrRatio").asDouble()).isEqualTo(1.2);
        assertThat(payload.path("hrv").get(0).path("lastNightAvg").asDouble()).isEqualTo(61);
        assertThat(payload.path("sleep").get(0).path("sleepTimeSeconds").asInt()).isEqualTo(28800);
        assertThat(payload.path("dailyHealth").get(0).path("restingHeartRate").asInt()).isEqualTo(48);
        assertThat(payload.path("activities").get(0).path("trainingStressScore").asInt()).isEqualTo(60);
        assertThat(payload.path("checkins").get(0).path("weightKg").asInt()).isEqualTo(71);
        assertThat(payload.path("checkins").get(0).path("rpe").asInt()).isEqualTo(3);
        assertThat(result.ftpWatts()).isEqualTo(213);
        assertThat(payload.toString()).doesNotContain("garminAccountId", "userId", "garminActivityId", "activityName", "deviceId", "private location", "\"id\"");
        var captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(training).selectList(captor.capture()); assertAccount(captor.getValue()); assertWindow(captor.getValue());
        verify(hrv).selectList(captor.capture()); assertAccount(captor.getValue()); assertWindow(captor.getValue());
        verify(sleep).selectList(captor.capture()); assertAccount(captor.getValue()); assertWindow(captor.getValue());
        verify(health).selectList(captor.capture()); assertAccount(captor.getValue()); assertWindow(captor.getValue());
        verify(ftp).selectList(captor.capture()); assertAccount(captor.getValue());
        assertPredicate(captor.getValue(), "effective_date <=", day);
        assertThat(captor.getValue().getSqlSegment()).contains("LIMIT 24");
        verify(activities).selectList(captor.capture()); assertAccount(captor.getValue());
        assertPredicate(captor.getValue(), "start_time_local >=", day.minusDays(27).atStartOfDay());
        assertPredicate(captor.getValue(), "start_time_local <", day.plusDays(1).atStartOfDay());
        assertThat(captor.getValue().getSqlSegment()).contains("LIMIT 60");
        verify(checkins).selectList(captor.capture()); assertPredicate(captor.getValue(), "user_id =", 7L); assertWindow(captor.getValue());
    }

    @Test
    void noAccountMakesNoUnscopedGarminQueriesAndKeepsMissingFieldsExplicit() {
        var advice = new TrainingAdviceEngine().evaluate(day, null, "source", null, List.of(), List.of(), null, List.of());
        var result = builder.build(7L, advice);
        assertThat(result.payload().path("hrv").isEmpty()).isTrue();
        assertThat(result.payload().path("currentFtpWatts").isNull()).isTrue();
        assertThat(result.maxMinutes()).isZero();
        verifyNoInteractions(training, hrv, sleep, health, ftp, activities);
    }

    private void assertWindow(Wrapper<?> wrapper) {
        assertPredicate(wrapper, "calendar_date BETWEEN", day.minusDays(27));
        assertPredicate(wrapper, "AND", day);
    }
    private void assertAccount(Wrapper<?> wrapper) { assertPredicate(wrapper, "garmin_account_id =", 11L); }
    private void assertPredicate(Wrapper<?> wrapper, String predicate, Object expected) {
        var match = Pattern.compile(Pattern.quote(predicate) + "\\s+#\\{ew.paramNameValuePairs.(\\w+)\\}").matcher(wrapper.getSqlSegment());
        assertThat(match.find()).as(predicate).isTrue();
        assertThat(((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs().get(match.group(1))).isEqualTo(expected);
    }
}
