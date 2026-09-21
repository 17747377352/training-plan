package com.trainingplan.platform.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.checkin.CheckinQuery;
import com.trainingplan.platform.dto.checkin.CheckinRequest;
import com.trainingplan.platform.dto.checkin.DailyCheckinDto;
import com.trainingplan.platform.entity.DailyCheckin;
import com.trainingplan.platform.mapper.DailyCheckinMapper;
import com.trainingplan.platform.service.impl.CheckinServiceImpl;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 手工打卡的增改、校验与用户边界。 */
@ExtendWith(MockitoExtension.class)
class CheckinServiceTest {

    private static final Long USER_ID = 7L;

    @Mock
    private DailyCheckinMapper dailyCheckinMapper;
    @Mock
    private UserService userService;

    private CheckinService checkinService;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "checkin-test");
        TableInfoHelper.initTableInfo(assistant, DailyCheckin.class);
        checkinService = new CheckinServiceImpl(dailyCheckinMapper, userService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldInsertNewCheckinUnderJwtUser() {
        when(dailyCheckinMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        CheckinRequest request = request(new BigDecimal("71.50"), 6, " 感觉不错 ");

        DailyCheckinDto saved = checkinService.saveCheckin(USER_ID, LocalDate.of(2026, 9, 21), request);

        assertThat(saved.weightKg()).isEqualByComparingTo("71.50");
        assertThat(saved.rpe()).isEqualTo(6);
        assertThat(saved.note()).isEqualTo("感觉不错");

        ArgumentCaptor<DailyCheckin> captor = ArgumentCaptor.forClass(DailyCheckin.class);
        verify(dailyCheckinMapper).insert(captor.capture());
        // 归属只能来自入参 userId，不能来自请求体
        assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().getCalendarDate()).isEqualTo(LocalDate.of(2026, 9, 21));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldQueryCheckinsScopedToJwtUser() {
        when(dailyCheckinMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        checkinService.listCheckins(USER_ID, new CheckinQuery());

        ArgumentCaptor<Wrapper<DailyCheckin>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(dailyCheckinMapper).selectList(captor.capture());
        // 只看日期不限定用户会读到别人的体重记录
        assertThat(captor.getValue().getTargetSql()).contains("user_id");
        assertThat(((AbstractWrapper<?, ?, ?>) captor.getValue()).getParamNameValuePairs().values())
                .contains(USER_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldDeleteOnlyJwtUsersCheckin() {
        checkinService.deleteCheckin(USER_ID, LocalDate.of(2026, 9, 21));

        ArgumentCaptor<Wrapper<DailyCheckin>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(dailyCheckinMapper).delete(captor.capture());
        assertThat(captor.getValue().getTargetSql()).contains("user_id");
        assertThat(((AbstractWrapper<?, ?, ?>) captor.getValue()).getParamNameValuePairs().values())
                .contains(USER_ID);
    }

    @Test
    void shouldUpdateExistingCheckinOnSameDate() {
        DailyCheckin existing = new DailyCheckin();
        existing.setId(5L);
        existing.setUserId(USER_ID);
        when(dailyCheckinMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        checkinService.saveCheckin(USER_ID, LocalDate.of(2026, 9, 21),
                request(new BigDecimal("70.00"), null, null));

        verify(dailyCheckinMapper).updateById(any(DailyCheckin.class));
        verify(dailyCheckinMapper, never()).insert(any(DailyCheckin.class));
    }

    @Test
    void shouldRejectCheckinWithoutAnyContent() {
        assertThatThrownBy(() -> checkinService.saveCheckin(USER_ID, LocalDate.of(2026, 9, 21),
                request(null, null, "   ")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PARAM_ERROR);

        verify(dailyCheckinMapper, never()).insert(any(DailyCheckin.class));
    }

    @Test
    void shouldRejectReversedQueryRange() {
        CheckinQuery query = new CheckinQuery();
        query.setStartDate(LocalDate.of(2026, 9, 21));
        query.setEndDate(LocalDate.of(2026, 9, 1));

        assertThatThrownBy(() -> checkinService.listCheckins(USER_ID, query))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PARAM_ERROR);
    }

    private CheckinRequest request(BigDecimal weight, Integer rpe, String note) {
        CheckinRequest request = new CheckinRequest();
        request.setWeightKg(weight);
        request.setRpe(rpe);
        request.setNote(note);
        return request;
    }
}
