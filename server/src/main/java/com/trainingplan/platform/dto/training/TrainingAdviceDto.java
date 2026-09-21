package com.trainingplan.platform.dto.training;

import java.time.LocalDate;
import java.util.List;

/** 可追溯的单日训练建议；context 指标不参与恢复灯色计算。 */
public record TrainingAdviceDto(
        LocalDate calendarDate, String ruleVersion, Long garminAccountId,
        String sourceDescription, Light light, String headline, String summary,
        String confidence, int availableRecoverySignals, List<Factor> factors,
        List<String> actions, Prescription prescription, Double wattsPerKg) {

    public enum Light { GREEN, YELLOW, RED, UNKNOWN, INFO }

    public record Factor(String key, String label, Light light, boolean recoverySignal,
                         boolean available, LocalDate sourceDate, String value, String explanation) {}

    public record Prescription(String type, String title, int durationMinutes,
                               String intensity, String purpose, List<Step> steps,
                               String adjustment) {}

    public record Step(String name, int minutes, Integer ftpPercentMin, Integer ftpPercentMax,
                       Integer powerMinWatts, Integer powerMaxWatts, String effort) {}
}
