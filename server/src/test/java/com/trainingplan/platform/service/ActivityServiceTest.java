package com.trainingplan.platform.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.trainingplan.platform.common.api.PageResult;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.activity.ActivityDetailDto;
import com.trainingplan.platform.dto.activity.ActivityQuery;
import com.trainingplan.platform.dto.activity.ActivitySummaryDto;
import com.trainingplan.platform.entity.Activity;
import com.trainingplan.platform.entity.GarminAccount;
import com.trainingplan.platform.mapper.ActivityMapper;
import com.trainingplan.platform.mapper.GarminAccountMapper;
import com.trainingplan.platform.service.impl.ActivityServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityServiceTest {

    @Mock
    private ActivityMapper activityMapper;
    @Mock
    private GarminAccountMapper garminAccountMapper;
    @Mock
    private UserService userService;

    private ActivityService activityService;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "activity-test"),
                GarminAccount.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "activity-test"),
                Activity.class);
        activityService = new ActivityServiceImpl(activityMapper, garminAccountMapper, userService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnOnlyActivitiesFromCurrentUsersAccounts() {
        GarminAccount account = new GarminAccount();
        account.setId(11L);
        when(garminAccountMapper.selectList(any(Wrapper.class))).thenReturn(List.of(account));

        Activity activity = activity();
        Page<Activity> page = new Page<>(1, 20);
        page.setTotal(1L);
        page.setRecords(List.of(activity));
        when(activityMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(page);

        PageResult<ActivitySummaryDto> result = activityService.listActivities(7L, new ActivityQuery());

        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.records()).singleElement().satisfies(summary -> {
            assertThat(summary.id()).isEqualTo(101L);
            assertThat(summary.activityName()).isEqualTo("周末长距离");
            assertThat(summary.distanceMeters()).isEqualTo(112_030D);
            assertThat(summary.normPower()).isEqualTo(168D);
        });
        verify(activityMapper).selectPage(any(IPage.class), any(Wrapper.class));
        verify(userService).getProfile(7L);
    }

    @Test
    void shouldReturnEmptyPageWithoutQueryingActivitiesWhenUserHasNoGarminAccount() {
        when(garminAccountMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        PageResult<ActivitySummaryDto> result = activityService.listActivities(7L, new ActivityQuery());

        assertThat(result.total()).isZero();
        assertThat(result.records()).isEmpty();
        verify(activityMapper, never()).selectPage(any(), any());
    }

    @Test
    void shouldRejectReversedDateRangeBeforeAccessingDatabase() {
        ActivityQuery query = new ActivityQuery();
        query.setStartDate(LocalDate.of(2026, 9, 21));
        query.setEndDate(LocalDate.of(2026, 9, 1));

        assertThatThrownBy(() -> activityService.listActivities(7L, query))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PARAM_ERROR);

        verify(garminAccountMapper, never()).selectList(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldListDistinctActivityTypesForCurrentUser() {
        GarminAccount account = new GarminAccount();
        account.setId(11L);
        when(garminAccountMapper.selectList(any(Wrapper.class))).thenReturn(List.of(account));

        Activity road = new Activity();
        road.setActivityTypeKey("road_biking");
        Activity indoor = new Activity();
        indoor.setActivityTypeKey("indoor_cycling");
        when(activityMapper.selectList(any(Wrapper.class))).thenReturn(List.of(indoor, road));

        assertThat(activityService.listActivityTypes(7L))
                .containsExactly("indoor_cycling", "road_biking");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnOwnedActivityDetail() {
        GarminAccount account = new GarminAccount();
        account.setId(11L);
        when(garminAccountMapper.selectList(any(Wrapper.class))).thenReturn(List.of(account));

        Activity activity = activity();
        activity.setGarminAccountId(11L);
        activity.setGarminActivityId(998877L);
        activity.setAvgCadence(88D);
        activity.setAvgLeftBalance(49.3D);
        activity.setPowerZone2Seconds(8_200D);
        activity.setDeviceId(5566L);
        when(activityMapper.selectOne(any(Wrapper.class))).thenReturn(activity);

        ActivityDetailDto result = activityService.getActivity(7L, 101L);

        assertThat(result.id()).isEqualTo(101L);
        assertThat(result.garminActivityId()).isEqualTo("998877");
        assertThat(result.avgCadence()).isEqualTo(88D);
        assertThat(result.avgLeftBalance()).isEqualTo(49.3D);
        assertThat(result.powerZone2Seconds()).isEqualTo(8_200D);
        assertThat(result.deviceId()).isEqualTo("5566");
        verify(userService).getProfile(7L);
        verify(activityMapper).selectOne(any(Wrapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldHideActivityOutsideCurrentUsersAccounts() {
        GarminAccount account = new GarminAccount();
        account.setId(11L);
        when(garminAccountMapper.selectList(any(Wrapper.class))).thenReturn(List.of(account));
        when(activityMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> activityService.getActivity(7L, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void detailDtoShouldCoverEveryPersistedActivityField() {
        assertThat(Arrays.stream(ActivityDetailDto.class.getRecordComponents())
                .map(component -> component.getName()))
                .containsExactlyInAnyOrder(
                        Arrays.stream(Activity.class.getDeclaredFields())
                                .filter(field -> !field.isSynthetic())
                                .map(field -> field.getName())
                                .toArray(String[]::new));
    }

    private Activity activity() {
        Activity activity = new Activity();
        activity.setId(101L);
        activity.setGarminAccountId(11L);
        activity.setActivityTypeKey("road_biking");
        activity.setActivityName("周末长距离");
        activity.setStartTimeLocal(LocalDateTime.of(2026, 9, 19, 8, 30));
        activity.setMovingDurationSeconds(14_400);
        activity.setDistanceMeters(112_030D);
        activity.setElevationGain(805D);
        activity.setAverageHr(158);
        activity.setAvgPower(142D);
        activity.setNormPower(168D);
        activity.setTrainingStressScore(235D);
        activity.setIntensityFactor(0.78D);
        return activity;
    }
}
