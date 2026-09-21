package com.trainingplan.platform.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.training.TrainingGoalDto;
import com.trainingplan.platform.dto.training.TrainingGoalRequest;
import com.trainingplan.platform.entity.TrainingGoal;
import com.trainingplan.platform.mapper.TrainingGoalMapper;
import com.trainingplan.platform.service.impl.TrainingGoalServiceImpl;
import com.trainingplan.platform.service.training.TrainingGoalType;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 训练目标的增改、校验与用户边界。 */
@ExtendWith(MockitoExtension.class)
class TrainingGoalServiceTest {

    private static final Long USER_ID = 7L;

    @Mock
    private TrainingGoalMapper trainingGoalMapper;
    @Mock
    private UserService userService;

    private TrainingGoalService trainingGoalService;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "goal-test"), TrainingGoal.class);
        trainingGoalService = new TrainingGoalServiceImpl(trainingGoalMapper, userService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void savesGoalUnderJwtUserWithLabelsAndDaysToTarget() {
        when(trainingGoalMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        LocalDate target = LocalDate.now().plusDays(60);
        TrainingGoalRequest request = request("POWER", target, 4, 480, " 十月绕圈赛 ");

        TrainingGoalDto saved = trainingGoalService.saveGoal(USER_ID, request);

        assertThat(saved.goalType()).isEqualTo("POWER");
        assertThat(saved.goalLabel()).isEqualTo("提升功率");
        assertThat(saved.daysToTarget()).isEqualTo(60);
        assertThat(saved.weeklySessions()).isEqualTo(4);
        assertThat(saved.weeklyMinutes()).isEqualTo(480);
        assertThat(saved.description()).isEqualTo("十月绕圈赛");

        ArgumentCaptor<TrainingGoal> captor = ArgumentCaptor.forClass(TrainingGoal.class);
        verify(trainingGoalMapper).insert(captor.capture());
        // 归属只能来自入参 userId，不能来自请求体
        assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void overwritesExistingGoalInsteadOfInsertingSecond() {
        TrainingGoal existing = new TrainingGoal();
        existing.setId(3L);
        existing.setUserId(USER_ID);
        when(trainingGoalMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        trainingGoalService.saveGoal(USER_ID, request("MUSCLE", null, 5, null, null));

        verify(trainingGoalMapper).updateById(any(TrainingGoal.class));
        verify(trainingGoalMapper, never()).insert(any(TrainingGoal.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void scopesGoalLookupToJwtUser() {
        when(trainingGoalMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertThat(trainingGoalService.getGoal(USER_ID)).isNull();

        ArgumentCaptor<Wrapper<TrainingGoal>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(trainingGoalMapper).selectOne(captor.capture());
        // 只按 id 查会把别人的目标读出来
        assertThat(captor.getValue().getTargetSql()).contains("user_id");
        assertThat(((AbstractWrapper<?, ?, ?>) captor.getValue()).getParamNameValuePairs().values())
                .contains(USER_ID);
        verify(userService).getProfile(USER_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteIsScopedToJwtUser() {
        trainingGoalService.deleteGoal(USER_ID);

        ArgumentCaptor<Wrapper<TrainingGoal>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(trainingGoalMapper).delete(captor.capture());
        assertThat(captor.getValue().getTargetSql()).contains("user_id");
        assertThat(((AbstractWrapper<?, ?, ?>) captor.getValue()).getParamNameValuePairs().values())
                .contains(USER_ID);
    }

    @Test
    void rejectsUnknownGoalType() {
        assertThatThrownBy(() -> trainingGoalService.saveGoal(USER_ID,
                request("BECOME_PRO", null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PARAM_ERROR);
        verify(trainingGoalMapper, never()).insert(any(TrainingGoal.class));
    }

    @Test
    void rejectsTargetDateInThePast() {
        assertThatThrownBy(() -> trainingGoalService.saveGoal(USER_ID,
                request("POWER", LocalDate.now().minusDays(1), null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PARAM_ERROR);
    }

    @Test
    void blankDescriptionIsStoredAsNull() {
        when(trainingGoalMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        TrainingGoalDto saved = trainingGoalService.saveGoal(USER_ID,
                request("GENERAL", null, null, null, "   "));

        assertThat(saved.description()).isNull();
    }

    /**
     * 请求校验用的正则必须与枚举一致，否则会出现「校验放行但服务层拒绝」的错位。
     */
    @Test
    void requestPatternMatchesEveryGoalTypeCode() throws Exception {
        String regex = TrainingGoalRequest.class.getDeclaredField("goalType")
                .getAnnotation(jakarta.validation.constraints.Pattern.class).regexp();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex);
        for (TrainingGoalType type : TrainingGoalType.values()) {
            assertThat(pattern.matcher(type.name()).matches())
                    .as("枚举值 %s 未包含在请求校验正则中", type.name()).isTrue();
        }
        assertThat(pattern.matcher("BECOME_PRO").matches()).isFalse();
    }

    private TrainingGoalRequest request(String type, LocalDate target, Integer sessions,
                                        Integer minutes, String description) {
        TrainingGoalRequest request = new TrainingGoalRequest();
        request.setGoalType(type);
        request.setTargetDate(target);
        request.setWeeklySessions(sessions);
        request.setWeeklyMinutes(minutes);
        request.setDescription(description);
        return request;
    }
}
