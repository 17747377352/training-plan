package com.trainingplan.platform.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.health.DailyHealthTrendDto;
import com.trainingplan.platform.dto.health.HrvTrendDto;
import com.trainingplan.platform.dto.health.SleepTrendDto;
import com.trainingplan.platform.dto.health.TrendQuery;
import com.trainingplan.platform.entity.DailyHealth;
import com.trainingplan.platform.entity.GarminAccount;
import com.trainingplan.platform.entity.HrvRecord;
import com.trainingplan.platform.entity.NapRecord;
import com.trainingplan.platform.entity.SleepRecord;
import com.trainingplan.platform.mapper.DailyHealthMapper;
import com.trainingplan.platform.mapper.GarminAccountMapper;
import com.trainingplan.platform.mapper.HrvRecordMapper;
import com.trainingplan.platform.mapper.NapRecordMapper;
import com.trainingplan.platform.mapper.SleepRecordMapper;
import com.trainingplan.platform.service.impl.HealthTrendServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthTrendServiceTest {

    @Mock
    private DailyHealthMapper dailyHealthMapper;
    @Mock
    private HrvRecordMapper hrvRecordMapper;
    @Mock
    private NapRecordMapper napRecordMapper;
    @Mock
    private SleepRecordMapper sleepRecordMapper;
    @Mock
    private GarminAccountMapper garminAccountMapper;
    @Mock
    private UserService userService;

    private HealthTrendService healthTrendService;

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "health-trend-test");
        TableInfoHelper.initTableInfo(assistant, GarminAccount.class);
        TableInfoHelper.initTableInfo(assistant, DailyHealth.class);
        TableInfoHelper.initTableInfo(assistant, HrvRecord.class);
        TableInfoHelper.initTableInfo(assistant, SleepRecord.class);
        TableInfoHelper.initTableInfo(assistant, NapRecord.class);
        healthTrendService = new HealthTrendServiceImpl(
                dailyHealthMapper,
                hrvRecordMapper,
                napRecordMapper,
                sleepRecordMapper,
                garminAccountMapper,
                userService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnCurrentUsersHealthSleepAndHrvRecords() {
        GarminAccount account = new GarminAccount();
        account.setId(16L);
        when(garminAccountMapper.selectList(any(Wrapper.class))).thenReturn(List.of(account));

        DailyHealth daily = new DailyHealth();
        daily.setCalendarDate(LocalDate.of(2026, 9, 20));
        daily.setSteps(8_800);
        daily.setRestingHeartRate(51);
        when(dailyHealthMapper.selectList(any(Wrapper.class))).thenReturn(List.of(daily));

        HrvRecord hrv = new HrvRecord();
        hrv.setCalendarDate(LocalDate.of(2026, 9, 20));
        hrv.setLastNightAvg(78D);
        hrv.setWeeklyAvg(81D);
        hrv.setHrvStatus("BALANCED");
        when(hrvRecordMapper.selectList(any(Wrapper.class))).thenReturn(List.of(hrv));

        SleepRecord sleep = new SleepRecord();
        sleep.setCalendarDate(LocalDate.of(2026, 9, 20));
        sleep.setSleepStartGmt(LocalDateTime.of(2026, 9, 19, 17, 0));
        sleep.setSleepTimeSeconds(27_000);
        sleep.setSleepScore(82);
        when(sleepRecordMapper.selectList(any(Wrapper.class))).thenReturn(List.of(sleep));

        TrendQuery query = query(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 21));
        List<DailyHealthTrendDto> dailyResult = healthTrendService.listDailyHealth(7L, query);
        List<HrvTrendDto> hrvResult = healthTrendService.listHrv(7L, query);
        List<SleepTrendDto> sleepResult = healthTrendService.listSleep(7L, query);

        assertThat(dailyResult).singleElement().satisfies(row -> {
            assertThat(row.steps()).isEqualTo(8_800);
            assertThat(row.restingHeartRate()).isEqualTo(51);
        });
        assertThat(hrvResult).singleElement().satisfies(row -> {
            assertThat(row.lastNightAvg()).isEqualTo(78D);
            assertThat(row.hrvStatus()).isEqualTo("BALANCED");
        });
        assertThat(sleepResult).singleElement().satisfies(row -> {
            assertThat(row.sleepTimeSeconds()).isEqualTo(27_000);
            assertThat(row.sleepScore()).isEqualTo(82);
        });
        verify(userService, times(3)).getProfile(7L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnEmptyWithoutQueryingHealthTablesWhenUserHasNoGarminAccount() {
        when(garminAccountMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertThat(healthTrendService.listDailyHealth(7L, new TrendQuery())).isEmpty();

        verify(dailyHealthMapper, never()).selectList(any());
        verify(hrvRecordMapper, never()).selectList(any());
        verify(sleepRecordMapper, never()).selectList(any());
    }

    @Test
    void shouldRejectReversedDateRange() {
        TrendQuery query = query(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 1));

        assertThatThrownBy(() -> healthTrendService.listHrv(7L, query))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PARAM_ERROR);

        verify(garminAccountMapper, never()).selectList(any());
    }

    @Test
    void shouldRejectMoreThan366Days() {
        TrendQuery query = query(LocalDate.of(2025, 9, 20), LocalDate.of(2026, 9, 21));

        assertThatThrownBy(() -> healthTrendService.listSleep(7L, query))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PARAM_ERROR);
    }

    /**
     * 账号归属必须来自入参 userId，而不是任何请求参数。
     *
     * <p>这里让桩按「查询条件里是否带上了这个 userId」决定返回，因此
     * 一旦实现里去掉 {@code eq(GarminAccount::getUserId, userId)}，
     * 用户 7 也会查不到账号，本用例会直接失败。</p>
     */
    @Test
    @SuppressWarnings("unchecked")
    void shouldResolveGarminAccountByCallerIdentityOnly() {
        GarminAccount account = new GarminAccount();
        account.setId(16L);
        when(garminAccountMapper.selectList(any(Wrapper.class))).thenAnswer(invocation -> {
            Wrapper<GarminAccount> wrapper = invocation.getArgument(0);
            // 必须按 user_id 比对，不能按主键或其他列
            return querySql(wrapper).contains("user_id")
                            && queryParams(wrapper).containsValue(7L)
                    ? List.of(account)
                    : List.of();
        });
        when(dailyHealthMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        TrendQuery query = query(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 21));

        healthTrendService.listDailyHealth(7L, query);
        verify(dailyHealthMapper).selectList(any(Wrapper.class));

        assertThat(healthTrendService.listDailyHealth(8L, query)).isEmpty();
        // 用户 8 没有账号，不应该再碰健康数据表
        verify(dailyHealthMapper, times(1)).selectList(any(Wrapper.class));
    }

    /** 数据查询必须带上调用者的账号与查询区间，否则会读到别人的数据。 */
    @Test
    @SuppressWarnings("unchecked")
    void shouldScopeHealthQueryToCallersAccountAndDateRange() {
        GarminAccount account = new GarminAccount();
        account.setId(16L);
        when(garminAccountMapper.selectList(any(Wrapper.class))).thenReturn(List.of(account));
        when(dailyHealthMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        healthTrendService.listDailyHealth(7L, query(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 21)));

        ArgumentCaptor<Wrapper<DailyHealth>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(dailyHealthMapper).selectList(captor.capture());
        assertThat(querySql(captor.getValue()))
                .contains("garmin_account_id")
                .contains("calendar_date");
        assertThat(queryParams(captor.getValue()))
                .containsValue(16L)
                .containsValue(LocalDate.of(2026, 9, 1))
                .containsValue(LocalDate.of(2026, 9, 21));
    }

    /** 取出 wrapper 上真正会执行的查询条件。 */
    private static java.util.Map<String, Object> queryParams(Wrapper<?> wrapper) {
        return ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
    }

    /**
     * 取出 wrapper 生成的 SQL 片段。
     *
     * <p>只断言条件值是不够的：把 {@code eq(user_id, ?)} 改成 {@code eq(id, ?)} 时
     * 参数值完全一样，只有列名能区分。MyBatis-Plus 的条件值也是在生成 SQL
     * 片段时才写进 {@code paramNameValuePairs}，所以必须先调这个方法再读参数。</p>
     */
    private static String querySql(Wrapper<?> wrapper) {
        return wrapper.getTargetSql();
    }

    private TrendQuery query(LocalDate startDate, LocalDate endDate) {
        TrendQuery query = new TrendQuery();
        query.setStartDate(startDate);
        query.setEndDate(endDate);
        return query;
    }
}
