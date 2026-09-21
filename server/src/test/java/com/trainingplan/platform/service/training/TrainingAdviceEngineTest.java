package com.trainingplan.platform.service.training;

import com.trainingplan.platform.dto.training.TrainingAdviceDto;
import com.trainingplan.platform.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.trainingplan.platform.dto.training.TrainingAdviceDto.Light.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 验证业务判灯、时间边界与处方降级，避免缺数或功率换算引入错误建议。 */
class TrainingAdviceEngineTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 21);
    private final TrainingAdviceEngine engine = new TrainingAdviceEngine();
    private TrainingStatus training;
    private HrvRecord hrv;
    private SleepRecord sleep;
    private FtpHistory ftp;
    private DailyCheckin checkin;

    @BeforeEach
    void setUp() {
        training = new TrainingStatus();
        training.setCalendarDate(DAY);
        training.setTrainingStatusPhrase("PRODUCTIVE_6");
        training.setAcwrStatus("OPTIMAL");
        training.setAcwrRatio(1.1);
        hrv = hrv(DAY, 60);
        sleep = sleep(8 * 3600);
        ftp = new FtpHistory();
        ftp.setEffectiveDate(DAY.minusDays(20));
        ftp.setFtpWatts(213);
        checkin = new DailyCheckin();
        checkin.setCalendarDate(DAY);
        checkin.setRpe(3);
        checkin.setWeightKg(new BigDecimal("71"));
    }

    @Test
    void healthyDayProducesExplainableEnduranceAndPower() {
        training.setBalanceFeedbackPhrase("AEROBIC_LOW_SHORTAGE");
        var result = evaluate();
        assertThat(result.light()).isEqualTo(GREEN);
        assertThat(result.factors()).hasSize(6).allMatch(f -> f.sourceDate() != null && !f.explanation().isBlank());
        assertThat(result.availableRecoverySignals()).isEqualTo(4);
        assertThat(result.wattsPerKg()).isEqualTo(3.0);
        assertThat(result.prescription().durationMinutes()).isEqualTo(60);
        assertThat(result.prescription().steps()).extracting(s -> s.minutes()).containsExactly(10, 45, 5);
        assertThat(result.prescription().steps().get(1).powerMinWatts()).isEqualTo(128);
        assertThat(result.prescription().steps().get(1).powerMaxWatts()).isEqualTo(149);
    }

    @ParameterizedTest
    @CsvSource({"3,GREEN,ENDURANCE", "6,GREEN,ENDURANCE", "7,YELLOW,RECOVERY", "8,YELLOW,RECOVERY", "9,RED,REST", "10,RED,REST"})
    void fatigueBoundaryControlsPlan(int rpe, String light, String type) {
        checkin.setRpe(rpe);
        var result = evaluate();
        assertThat(result.light().name()).isEqualTo(light);
        assertThat(result.prescription().type()).isEqualTo(type);
        assertThat(result.prescription().steps().stream().mapToInt(s -> s.minutes()).sum())
                .isEqualTo(result.prescription().durationMinutes());
    }

    @ParameterizedTest
    @CsvSource({"14399,RED", "14400,YELLOW", "21599,YELLOW", "21600,GREEN"})
    void sleepDurationBoundaries(int seconds, String light) {
        sleep.setSleepTimeSeconds(seconds);
        assertThat(evaluate().light().name()).isEqualTo(light);
    }

    @Test
    void twoIndependentWarningsEscalateButMissingSignalsDoNotCountAsWarnings() {
        checkin.setRpe(7);
        sleep.setSleepTimeSeconds(5 * 3600);
        assertThat(evaluate().light()).isEqualTo(RED);
        var partial = engine.evaluate(DAY, 11L, "test", training, List.of(), List.of(), ftp, List.of(checkin));
        assertThat(partial.light()).isEqualTo(YELLOW);
        assertThat(partial.confidence()).isEqualTo("MEDIUM");
    }

    @Test
    void noDataIsYellowWithoutPrescribingExercise() {
        var result = engine.evaluate(DAY, null, "test", null, List.of(), List.of(), null, List.of());
        assertThat(result.light()).isEqualTo(YELLOW);
        assertThat(result.availableRecoverySignals()).isZero();
        assertThat(result.prescription().type()).isEqualTo("REST");
        assertThat(result.actions()).hasSize(4);
    }

    @Test
    void staleAndFutureRecoveryDataCannotProduceGreen() {
        training.setCalendarDate(DAY.minusDays(2));
        hrv.setCalendarDate(DAY.minusDays(1));
        sleep.setCalendarDate(DAY.plusDays(1));
        checkin.setCalendarDate(DAY.minusDays(1));
        var result = evaluate();
        assertThat(result.light()).isEqualTo(YELLOW);
        assertThat(result.availableRecoverySignals()).isZero();
        assertThat(result.factors().get(1).sourceDate()).isEqualTo(DAY.minusDays(1));
    }

    @Test
    void yesterdayTrainingIsAllowedButYesterdayFatigueIsNot() {
        training.setCalendarDate(DAY.minusDays(1));
        assertThat(evaluate().light()).isEqualTo(GREEN);
        checkin.setCalendarDate(DAY.minusDays(1));
        assertThat(evaluate().light()).isEqualTo(YELLOW);
    }

    @Test
    void napAndDuplicateSleepNeverReplaceOrSumMainSleep() {
        var result = engine.evaluate(DAY, 11L, "test", training, List.of(hrv),
                List.of(sleep(3600), sleep, sleep), ftp, List.of(checkin));
        assertThat(result.factors().get(2).value()).contains("8.0");
        assertThat(result.light()).isEqualTo(GREEN);
    }

    @Test
    void lowSleepScoreTriggersYellowDespiteLongSleep() {
        sleep.setSleepScore(59);
        assertThat(evaluate().light()).isEqualTo(YELLOW);
    }

    @Test
    void hrvNeedsBaselineOrRecognizedPersonalStatus() {
        hrv.setBaselineBalancedLow(null);
        hrv.setHrvStatus("UNKNOWN");
        assertThat(evaluate().factors().get(1).available()).isFalse();
        assertThat(evaluate().light()).isEqualTo(YELLOW);
    }

    @Test
    void threeConsecutiveHrvWarningsTriggerRestButDateGapsDoNot() {
        hrv.setWeeklyAvg(45.0);
        var continuous = engine.evaluate(DAY, 11L, "test", training,
                List.of(hrv, hrv(DAY.minusDays(1), 45), hrv(DAY.minusDays(2), 45)), List.of(sleep), ftp, List.of(checkin));
        assertThat(continuous.light()).isEqualTo(RED);
        var gaps = engine.evaluate(DAY, 11L, "test", training,
                List.of(hrv, hrv(DAY.minusDays(2), 45), hrv(DAY.minusDays(3), 45)), List.of(sleep), ftp, List.of(checkin));
        assertThat(gaps.light()).isEqualTo(YELLOW);
    }

    @Test
    void unusuallyHighHrvAndOvernightDropBothNeedAttention() {
        hrv.setWeeklyAvg(90.0);
        assertThat(evaluate().light()).isEqualTo(YELLOW);
        hrv.setWeeklyAvg(60.0);
        hrv.setLastNightAvg(48.0);
        assertThat(evaluate().light()).isEqualTo(YELLOW);
    }

    @ParameterizedTest
    @CsvSource({"1.49,GREEN", "1.5,YELLOW", "1.99,YELLOW", "2.0,RED"})
    void acwrUsesRatioAndDoesNotTreatUiPercentAsRatio(double ratio, String light) {
        training.setAcwrRatio(ratio);
        training.setAcwrPercent(200);
        assertThat(evaluate().light().name()).isEqualTo(light);
    }

    @Test
    void trainingStateOverridesOptimalAcwrAndStrongFtp() {
        training.setTrainingStatusPhrase("OVERREACHING_9");
        ftp.setFtpWatts(400);
        assertThat(evaluate().light()).isEqualTo(RED);
        assertThat(evaluate().prescription().steps()).isEmpty();
    }

    @Test
    void unknownEmptyTrainingSnapshotIsNotNormal() {
        training.setTrainingStatusPhrase("NO_STATUS_1");
        training.setAcwrStatus(null);
        training.setAcwrRatio(null);
        assertThat(evaluate().factors().get(0).available()).isFalse();
        assertThat(evaluate().light()).isEqualTo(YELLOW);
    }

    @Test
    void ftpAndWeightDoNotVoteOnReadinessAndExpiredFtpCannotSetWatts() {
        ftp.setEffectiveDate(DAY.minusDays(91));
        checkin.setWeightKg(null);
        var result = evaluate();
        assertThat(result.light()).isEqualTo(GREEN);
        assertThat(result.wattsPerKg()).isNull();
        assertThat(result.prescription().steps()).allMatch(s -> s.powerMinWatts() == null && s.powerMaxWatts() == null);
    }

    @Test
    void ftpAgeBoundaryAndFutureFtpAreHandled() {
        ftp.setEffectiveDate(DAY.minusDays(90));
        assertThat(evaluate().factors().get(4).available()).isTrue();
        ftp.setEffectiveDate(DAY.plusDays(1));
        assertThat(evaluate().factors().get(4).available()).isFalse();
    }

    @Test
    void latestNonNullWeightIsIndependentOfTodaysFatigue() {
        DailyCheckin previous = new DailyCheckin();
        previous.setCalendarDate(DAY.minusDays(14));
        previous.setWeightKg(new BigDecimal("71"));
        checkin.setWeightKg(null);
        var result = engine.evaluate(DAY, 11L, "test", training, List.of(hrv), List.of(sleep), ftp, List.of(previous, checkin));
        assertThat(result.wattsPerKg()).isEqualTo(3.0);
        previous.setCalendarDate(DAY.minusDays(15));
        assertThat(engine.evaluate(DAY, 11L, "test", training, List.of(hrv), List.of(sleep), ftp, List.of(previous, checkin)).wattsPerKg()).isNull();
    }

    @Test
    void invalidValuesAreNotUsable() {
        ftp.setFtpWatts(0);
        checkin.setWeightKg(BigDecimal.ZERO);
        checkin.setRpe(0);
        sleep.setSleepTimeSeconds(0);
        assertThat(evaluate().availableRecoverySignals()).isEqualTo(2);
        assertThat(evaluate().wattsPerKg()).isNull();
        assertThat(evaluate().light()).isEqualTo(YELLOW);
    }

    private TrainingAdviceDto evaluate() {
        return engine.evaluate(DAY, 11L, "test", training, List.of(hrv), List.of(sleep), ftp, List.of(checkin));
    }

    private HrvRecord hrv(LocalDate day, double weekly) {
        HrvRecord row = new HrvRecord();
        row.setCalendarDate(day);
        row.setWeeklyAvg(weekly);
        row.setLastNightAvg(weekly);
        row.setBaselineBalancedLow(50.0);
        row.setBaselineBalancedUpper(80.0);
        row.setHrvStatus("BALANCED");
        return row;
    }

    private SleepRecord sleep(int seconds) {
        SleepRecord row = new SleepRecord();
        row.setCalendarDate(DAY);
        row.setSleepTimeSeconds(seconds);
        row.setSleepScore(80);
        return row;
    }
}
