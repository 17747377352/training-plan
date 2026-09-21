package com.trainingplan.platform.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.entity.*;
import com.trainingplan.platform.mapper.*;
import com.trainingplan.platform.service.impl.TrainingAdviceServiceImpl;
import com.trainingplan.platform.service.training.TrainingAdviceEngine;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 同时核对 WHERE 列和绑定参数，验证用户归属、单账号一致性及历史日期边界。 */
@ExtendWith(MockitoExtension.class)
class TrainingAdviceServiceTest {
    @Mock private UserService userService;
    @Mock private GarminAccountMapper accounts;
    @Mock private TrainingStatusMapper training;
    @Mock private HrvRecordMapper hrv;
    @Mock private SleepRecordMapper sleep;
    @Mock private FtpHistoryMapper ftp;
    @Mock private DailyCheckinMapper checkins;
    private TrainingAdviceService service;
    private final LocalDate day = LocalDate.of(2026, 9, 20);

    @BeforeEach
    void setUp() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "advice-test");
        for (Class<?> entity : List.of(GarminAccount.class, TrainingStatus.class, HrvRecord.class,
                SleepRecord.class, FtpHistory.class, DailyCheckin.class)) TableInfoHelper.initTableInfo(assistant, entity);
        service = new TrainingAdviceServiceImpl(userService, accounts, training, hrv, sleep, ftp, checkins, new TrainingAdviceEngine());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void allQueriesAreScopedAndNeverReadBeyondRequestedDate() {
        when(accounts.selectList(any(Wrapper.class))).thenReturn(List.of(account(11L), account(12L)));
        TrainingStatus row = new TrainingStatus();
        row.setGarminAccountId(12L);
        row.setCalendarDate(day);
        when(training.selectList(any(Wrapper.class))).thenReturn(List.of(row));
        var result = service.getAdvice(7L, day);
        assertThat(result.garminAccountId()).isEqualTo(12L);
        verify(userService).getProfile(7L);
        var captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(accounts).selectList(captor.capture());
        assertBinding(captor.getValue(), "user_id =", 7L);
        verify(checkins).selectList(captor.capture());
        assertBinding(captor.getValue(), "user_id =", 7L);
        assertDateWindow(captor.getValue());
        verify(training).selectList(captor.capture());
        assertBinding(captor.getValue(), "garmin_account_id IN", 11L, 12L);
        assertDateWindow(captor.getValue());
        assertThat(captor.getValue().getSqlSegment()).contains("ORDER BY calendar_date DESC,id DESC", "LIMIT 1");
        verify(hrv).selectList(captor.capture());
        assertBinding(captor.getValue(), "garmin_account_id =", 12L);
        assertDateWindow(captor.getValue());
        verify(sleep).selectList(captor.capture());
        assertBinding(captor.getValue(), "garmin_account_id =", 12L);
        assertDateWindow(captor.getValue());
        verify(ftp).selectList(captor.capture());
        assertBinding(captor.getValue(), "garmin_account_id =", 12L);
        assertBinding(captor.getValue(), "effective_date <=", day);
        assertThat(captor.getValue().getSqlSegment()).contains("ORDER BY effective_date DESC,id DESC", "LIMIT 1");
    }

    @Test
    void noGarminAccountStillUsesOwnCheckinWithoutUnscopedGarminReads() {
        DailyCheckin checkin = new DailyCheckin();
        checkin.setCalendarDate(day);
        checkin.setRpe(9);
        when(checkins.selectList(any(Wrapper.class))).thenReturn(List.of(checkin));
        var result = service.getAdvice(7L, day);
        assertThat(result.light().name()).isEqualTo("RED");
        verifyNoInteractions(training, hrv, sleep, ftp);
    }

    @Test
    void absentTrainingSelectsDeterministicAccountAndDoesNotMixAccounts() {
        when(accounts.selectList(any(Wrapper.class))).thenReturn(List.of(account(12L), account(11L)));
        assertThat(service.getAdvice(7L, day).garminAccountId()).isEqualTo(11L);
    }

    @Test
    void rejectsFutureDatesBeforeReadingData() {
        assertThatThrownBy(() -> service.getAdvice(7L, LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1)))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.PARAM_ERROR);
        verifyNoInteractions(accounts, training, hrv, sleep, ftp, checkins);
    }

    @Test
    void checksDisabledUserBeforeAnyDataAccess() {
        when(userService.getProfile(7L)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.getAdvice(7L, day)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(accounts, training, hrv, sleep, ftp, checkins);
    }

    @Test
    void defaultsToShanghaiDate() {
        assertThat(service.getAdvice(7L, null).calendarDate()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Shanghai")));
    }

    private GarminAccount account(Long id) {
        var account = new GarminAccount(); account.setId(id); account.setUserId(7L); return account;
    }

    private void assertDateWindow(Wrapper<?> wrapper) {
        assertBinding(wrapper, "calendar_date BETWEEN", day.minusDays(29));
        assertBinding(wrapper, "AND", day);
    }

    private void assertBinding(Wrapper<?> wrapper, String columnOperator, Object... values) {
        String sql = wrapper.getSqlSegment();
        var matcher = Pattern.compile(Pattern.quote(columnOperator) + "\\s+(?:\\()?((?:#\\{[^}]+}\\s*,?\\s*)+)").matcher(sql);
        assertThat(matcher.find()).as("WHERE predicate %s in %s", columnOperator, sql).isTrue();
        var keys = Pattern.compile("paramNameValuePairs\\.(\\w+)").matcher(matcher.group(1));
        var actual = new java.util.ArrayList<Object>();
        var params = ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
        while (keys.find()) actual.add(params.get(keys.group(1)));
        assertThat(actual).containsExactly(values);
    }
}
