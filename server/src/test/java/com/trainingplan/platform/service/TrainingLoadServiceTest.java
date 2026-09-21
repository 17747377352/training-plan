package com.trainingplan.platform.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.training.FtpDto;
import com.trainingplan.platform.dto.training.TrainingLoadTrendDto;
import com.trainingplan.platform.entity.FtpHistory;
import com.trainingplan.platform.entity.GarminAccount;
import com.trainingplan.platform.entity.TrainingStatus;
import com.trainingplan.platform.mapper.FtpHistoryMapper;
import com.trainingplan.platform.mapper.GarminAccountMapper;
import com.trainingplan.platform.mapper.TrainingStatusMapper;
import com.trainingplan.platform.service.impl.TrainingLoadServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 训练负荷与 FTP 查询的归属边界与参数校验。 */
@ExtendWith(MockitoExtension.class)
class TrainingLoadServiceTest {

    private static final Long USER_ID = 7L;

    @Mock
    private TrainingStatusMapper trainingStatusMapper;
    @Mock
    private FtpHistoryMapper ftpHistoryMapper;
    @Mock
    private GarminAccountMapper garminAccountMapper;
    @Mock
    private UserService userService;

    private TrainingLoadService trainingLoadService;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "training-load-test");
        TableInfoHelper.initTableInfo(assistant, TrainingStatus.class);
        TableInfoHelper.initTableInfo(assistant, FtpHistory.class);
        TableInfoHelper.initTableInfo(assistant, GarminAccount.class);
        trainingLoadService = new TrainingLoadServiceImpl(
                trainingStatusMapper, ftpHistoryMapper, garminAccountMapper, userService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldMapTrainingLoadAndScopeToCallersAccounts() {
        when(garminAccountMapper.selectList(any(Wrapper.class))).thenReturn(List.of(account()));
        TrainingStatus status = new TrainingStatus();
        status.setCalendarDate(LocalDate.of(2026, 9, 21));
        status.setTrainingStatusPhrase("PRODUCTIVE_6");
        status.setAcuteLoad(819);
        status.setChronicLoad(658);
        status.setAcwrPercent(52);
        status.setAcwrStatus("OPTIMAL");
        status.setLoadAerobicLow(157.42);
        status.setLoadAerobicLowTargetMin(433);
        status.setBalanceFeedbackPhrase("AEROBIC_LOW_SHORTAGE");
        status.setVo2maxValue(59.0);
        when(trainingStatusMapper.selectList(any(Wrapper.class))).thenReturn(List.of(status));

        List<TrainingLoadTrendDto> rows = trainingLoadService.listTrainingLoad(
                USER_ID, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 21));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.calendarDate()).isEqualTo(LocalDate.of(2026, 9, 21));
            assertThat(row.acuteLoad()).isEqualTo(819);
            assertThat(row.chronicLoad()).isEqualTo(658);
            assertThat(row.acwrStatus()).isEqualTo("OPTIMAL");
            assertThat(row.loadAerobicLow()).isEqualTo(157.42);
            assertThat(row.balanceFeedbackPhrase()).isEqualTo("AEROBIC_LOW_SHORTAGE");
            assertThat(row.vo2maxValue()).isEqualTo(59.0);
        });

        ArgumentCaptor<Wrapper<TrainingStatus>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(trainingStatusMapper).selectList(captor.capture());
        assertThat(captor.getValue().getTargetSql()).contains("garmin_account_id");
        assertThat(((AbstractWrapper<?, ?, ?>) captor.getValue()).getParamNameValuePairs().values())
                .contains(11L);
    }

    @Test
    void shouldReturnEmptyWithoutQueryingWhenUserHasNoAccount() {
        when(garminAccountMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertThat(trainingLoadService.listTrainingLoad(USER_ID, null, null)).isEmpty();
        assertThat(trainingLoadService.listFtp(USER_ID)).isEmpty();

        verify(trainingStatusMapper, never()).selectList(any());
        verify(ftpHistoryMapper, never()).selectList(any());
    }

    @Test
    void shouldRejectReversedRange() {
        assertThatThrownBy(() -> trainingLoadService.listTrainingLoad(
                USER_ID, LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 1)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PARAM_ERROR);

        verify(garminAccountMapper, never()).selectList(any());
    }

    @Test
    void shouldRejectRangeOver366Days() {
        assertThatThrownBy(() -> trainingLoadService.listTrainingLoad(
                USER_ID, LocalDate.of(2025, 9, 20), LocalDate.of(2026, 9, 21)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PARAM_ERROR);
    }

    @Test
    void shouldReturnFtpHistoryInEffectOrder() {
        when(garminAccountMapper.selectList(any(Wrapper.class))).thenReturn(List.of(account()));
        FtpHistory row = new FtpHistory();
        row.setEffectiveDate(LocalDate.of(2026, 8, 29));
        row.setFtpWatts(213);
        row.setSource("GARMIN");
        when(ftpHistoryMapper.selectList(any(Wrapper.class))).thenReturn(List.of(row));

        List<FtpDto> rows = trainingLoadService.listFtp(USER_ID);

        assertThat(rows).singleElement().satisfies(ftp -> {
            assertThat(ftp.effectiveDate()).isEqualTo(LocalDate.of(2026, 8, 29));
            assertThat(ftp.ftpWatts()).isEqualTo(213);
            assertThat(ftp.source()).isEqualTo("GARMIN");
        });
    }

    private GarminAccount account() {
        GarminAccount account = new GarminAccount();
        account.setId(11L);
        account.setUserId(USER_ID);
        return account;
    }
}
