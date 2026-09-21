package com.trainingplan.platform.service.training;

import com.trainingplan.platform.dto.training.TrainingAdviceDto;
import com.trainingplan.platform.dto.training.TrainingAdviceDto.*;
import com.trainingplan.platform.entity.*;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;

import static com.trainingplan.platform.dto.training.TrainingAdviceDto.Light.*;

/** 无 IO 的规则引擎。所有阈值是 v1 产品规则，详见 docs/训练建议规则.md。 */
@Component
public class TrainingAdviceEngine {
    public static final String RULE_VERSION = "readiness-v1";
    private static final int FTP_MAX_AGE_DAYS = 90;
    private static final int WEIGHT_MAX_AGE_DAYS = 14;
    private static final Set<String> NORMAL_TRAINING = Set.of("PRODUCTIVE", "MAINTAINING", "PEAKING");
    private static final Set<String> CAUTION_TRAINING = Set.of("STRAINED", "UNPRODUCTIVE", "RECOVERY", "DETRAINING");
    private static final Set<String> LOAD_STATES = Set.of("OPTIMAL", "LOW", "VERY_LOW", "HIGH", "VERY_HIGH");
    private static final Map<String, String> TRAINING_LABELS = Map.ofEntries(
            Map.entry("PRODUCTIVE", "有效训练"), Map.entry("MAINTAINING", "维持中"),
            Map.entry("PEAKING", "巅峰状态"), Map.entry("STRAINED", "恢复吃紧"),
            Map.entry("UNPRODUCTIVE", "训练无效"), Map.entry("RECOVERY", "恢复中"),
            Map.entry("DETRAINING", "停训退化"), Map.entry("OVERREACHING", "过度负荷"));

    public TrainingAdviceDto evaluate(LocalDate date, Long accountId, String source,
                                      TrainingStatus training, List<HrvRecord> hrv,
                                      List<SleepRecord> sleep, FtpHistory ftp,
                                      List<DailyCheckin> checkins) {
        DailyCheckin fatigue = latest(checkins.stream().filter(c -> c.getRpe() != null).toList(),
                DailyCheckin::getCalendarDate, date);
        DailyCheckin weight = latest(checkins.stream().filter(c -> c.getWeightKg() != null).toList(),
                DailyCheckin::getCalendarDate, date);
        List<Factor> factors = List.of(trainingFactor(date, training),
                hrvFactor(date, hrv), sleepFactor(date, sleep), fatigueFactor(date, fatigue),
                ftpFactor(date, ftp), weightFactor(date, weight));
        List<Factor> recovery = factors.stream().filter(Factor::recoverySignal).toList();
        int available = (int) recovery.stream().filter(Factor::available).count();
        long warnings = recovery.stream().filter(f -> f.light() == YELLOW).count();
        Light light = recovery.stream().anyMatch(f -> f.light() == RED) || warnings >= 2 ? RED
                : warnings > 0 || available < 4 ? YELLOW : GREEN;
        String headline = switch (light) {
            case GREEN -> "绿灯 · 可以进行有氧训练";
            case RED -> "红灯 · 今天优先休息";
            default -> "黄灯 · 今天保守安排";
        };
        String summary = light == RED
                ? (warnings >= 2 ? "至少两个恢复维度同时提示异常，今天取消强度训练。" : "出现明显恢复警报，今天取消训练课。")
                : available < 4 ? "恢复依据不完整，暂不放行强度训练；补齐数据后重新评估。"
                : light == YELLOW ? "有一个恢复维度需要留意，缩短时长并降低强度。"
                : "四项恢复依据均可用且未触发减量规则，今天以稳定有氧为主。";
        List<String> actions = new ArrayList<>();
        if (!factors.get(0).available() || !factors.get(1).available() || !factors.get(2).available()) {
            actions.add("同步 Garmin 的训练状态、当日 HRV 和昨夜睡眠后刷新建议。");
        }
        if (!factors.get(3).available()) actions.add("补填当天的主观疲劳（RPE）打卡：1 很轻松，10 极度疲劳。");
        if (!factors.get(4).available()) actions.add("FTP 缺失、无效或超过 90 天，暂按体感训练，确认当前 FTP 后再使用瓦数。");
        if (!factors.get(5).available()) actions.add("补填近 14 天体重以计算 W/kg；体重不参与恢复灯色判断。");
        Integer watts = factors.get(4).available() ? ftp.getFtpWatts() : null;
        Double wattsPerKg = watts != null && factors.get(5).available()
                ? Math.round(watts / weight.getWeightKg().doubleValue() * 100.0) / 100.0 : null;
        return new TrainingAdviceDto(date, RULE_VERSION, accountId, source, light, headline, summary,
                available == 4 ? "HIGH" : available >= 2 ? "MEDIUM" : "LOW", available,
                List.copyOf(factors), List.copyOf(actions), prescription(light, available, watts, training), wattsPerKg);
    }

    private Factor trainingFactor(LocalDate date, TrainingStatus row) {
        LocalDate day = row == null ? null : row.getCalendarDate();
        if (!fresh(date, day, 1)) return missing("training", "训练状态与负荷", true, day,
                "需要当天或前一天的训练快照；过期负荷不用于判灯。");
        String phrase = row.getTrainingStatusPhrase() == null ? "" : row.getTrainingStatusPhrase();
        String state = phrase.replaceFirst("_\\d+$", "");
        String load = row.getAcwrStatus() == null ? "" : row.getAcwrStatus();
        Double ratio = positive(row.getAcwrRatio()) ? row.getAcwrRatio() : null;
        List<String> reasons = new ArrayList<>();
        Light light = GREEN;
        if (state.equals("OVERREACHING")) reasons.add("Garmin 训练状态为过度负荷");
        if (CAUTION_TRAINING.contains(state)) reasons.add("Garmin 训练状态为" + TRAINING_LABELS.get(state));
        if (load.equals("VERY_HIGH")) reasons.add("Garmin 负荷等级过高");
        if (load.equals("HIGH")) reasons.add("Garmin 负荷等级偏高");
        if (ratio != null && ratio >= 1.5) reasons.add("ACWR 比值 " + number(ratio) + (ratio >= 2.0 ? " ≥ 2.0" : " ≥ 1.5"));
        if (state.equals("OVERREACHING") || load.equals("VERY_HIGH") || ratio != null && ratio >= 2.0) {
            light = RED;
        } else if (CAUTION_TRAINING.contains(state) || load.equals("HIGH") || ratio != null && ratio >= 1.5) {
            light = YELLOW;
        } else if (!NORMAL_TRAINING.contains(state) && !LOAD_STATES.contains(load) && ratio == null) {
            return missing("training", "训练状态与负荷", true, day, "没有可识别的训练状态或负荷比；不把空快照当正常。");
        } else {
            reasons.add("未触发负荷偏高或恢复状态规则；低负荷不自动要求补强度");
        }
        String value = TRAINING_LABELS.getOrDefault(state, "状态未提供")
                + " · ACWR " + (ratio == null ? "未提供" : String.format(Locale.ROOT, "%.2f", ratio))
                + " · 急性/慢性 " + Objects.toString(row.getAcuteLoad(), "—") + "/" + Objects.toString(row.getChronicLoad(), "—");
        return new Factor("training", "训练状态与负荷", light, true, true, day, value,
                String.join("；", reasons) + "。");
    }

    private Factor hrvFactor(LocalDate date, List<HrvRecord> rows) {
        HrvRecord row = latest(rows, HrvRecord::getCalendarDate, date);
        LocalDate day = row == null ? null : row.getCalendarDate();
        if (!fresh(date, day, 0)) return missing("hrv", "HRV", true, day, "需要当日的夜间 HRV / 7 日均值与个人基线，旧数据不替代今晨状态。");
        String status = Objects.toString(row.getHrvStatus(), "");
        boolean baseline = validBaseline(row);
        boolean recognized = Set.of("BALANCED", "UNBALANCED", "LOW", "POOR").contains(status);
        if (!baseline && !recognized) return missing("hrv", "HRV", true, day, "HRV 尚无个人基线或可用状态；绝对毫秒数不能跨人比较。");
        boolean warning = hrvWarning(row);
        boolean persistent = warning;
        for (int offset = 1; offset <= 2; offset++) {
            LocalDate previous = date.minusDays(offset);
            HrvRecord prior = rows.stream().filter(r -> previous.equals(r.getCalendarDate())).findFirst().orElse(null);
            persistent &= prior != null && hrvWarning(prior);
        }
        String value = "夜间 " + number(row.getLastNightAvg()) + " ms · 7 日 " + number(row.getWeeklyAvg()) + " ms";
        String explanation = baseline ? "7 日均值对照个人基线 " + number(row.getBaselineBalancedLow()) + "–"
                + number(row.getBaselineBalancedUpper()) + " ms。" : "采用 Garmin 个人 HRV 状态 " + status + "。";
        explanation += persistent ? "连续 3 个日历日触发 HRV 异常规则，优先恢复。"
                : warning ? "状态失衡、7 日均值超出基线，或夜间值较 7 日均值下降至少 20%，今天减量。"
                : "状态与个人基线未触发异常规则。";
        return new Factor("hrv", "HRV", persistent ? RED : warning ? YELLOW : GREEN,
                true, true, day, value, explanation);
    }

    private boolean validBaseline(HrvRecord r) {
        return positive(r.getWeeklyAvg()) && positive(r.getBaselineBalancedLow())
                && positive(r.getBaselineBalancedUpper()) && r.getBaselineBalancedLow() <= r.getBaselineBalancedUpper();
    }

    private boolean hrvWarning(HrvRecord r) {
        return Set.of("LOW", "POOR", "UNBALANCED").contains(Objects.toString(r.getHrvStatus(), ""))
                || validBaseline(r) && (r.getWeeklyAvg() < r.getBaselineBalancedLow() || r.getWeeklyAvg() > r.getBaselineBalancedUpper())
                || positive(r.getLastNightAvg()) && positive(r.getWeeklyAvg()) && r.getLastNightAvg() <= r.getWeeklyAvg() * 0.8;
    }

    private Factor sleepFactor(LocalDate date, List<SleepRecord> rows) {
        // 同一天仅取最长的有效睡眠记录，午睡不覆盖主睡眠，也不叠加重复记录。
        SleepRecord row = rows.stream().filter(r -> fresh(date, r.getCalendarDate(), 0))
                .filter(r -> r.getSleepTimeSeconds() != null && r.getSleepTimeSeconds() > 0 && r.getSleepTimeSeconds() <= 86400)
                .max(Comparator.comparing(SleepRecord::getSleepTimeSeconds)
                        .thenComparing(r -> Optional.ofNullable(r.getSleepStartGmt()).orElse(date.atStartOfDay())))
                .orElse(null);
        if (row == null) {
            SleepRecord last = latest(rows, SleepRecord::getCalendarDate, date);
            return missing("sleep", "睡眠", true, last == null ? null : last.getCalendarDate(), "需要归属当日的有效主睡眠时长，午睡与重复记录不累加。");
        }
        int seconds = row.getSleepTimeSeconds();
        Integer score = row.getSleepScore();
        boolean lowScore = score != null && score >= 0 && score < 60;
        Light light = seconds < 4 * 3600 ? RED : seconds < 6 * 3600 || lowScore ? YELLOW : GREEN;
        String value = String.format(Locale.ROOT, "%.1f 小时", seconds / 3600.0)
                + (score != null && score >= 0 && score <= 100 ? " · " + score + " 分" : " · 评分缺失");
        return new Factor("sleep", "睡眠", light, true, true, date, value,
                "取当天最长睡眠。少于 4 小时红灯；少于 6 小时或评分低于 60 黄灯；其余不触发减量。");
    }

    private Factor fatigueFactor(LocalDate date, DailyCheckin row) {
        LocalDate day = row == null ? null : row.getCalendarDate();
        if (!fresh(date, day, 0) || row.getRpe() == null || row.getRpe() < 1 || row.getRpe() > 10) {
            return missing("rpe", "主观疲劳 · RPE", true, day, "请填写当天疲劳 1–10；不沿用昨天的感受，也不把单次训练 RPE 当作晨间疲劳。");
        }
        int value = row.getRpe();
        return new Factor("rpe", "主观疲劳 · RPE", value >= 9 ? RED : value >= 7 ? YELLOW : GREEN,
                true, true, day, value + " / 10", "沿用本项目打卡口径：1 很轻松，10 极度疲劳。7–8 减量，9–10 休息。");
    }

    private Factor ftpFactor(LocalDate date, FtpHistory row) {
        LocalDate day = row == null ? null : row.getEffectiveDate();
        if (!fresh(date, day, FTP_MAX_AGE_DAYS) || row.getFtpWatts() == null || row.getFtpWatts() <= 0) {
            return missing("ftp", "FTP", false, day, "需要生效不超过 90 天且大于 0 的 FTP；不可用时省略目标瓦数。");
        }
        return new Factor("ftp", "FTP", INFO, false, true, day, row.getFtpWatts() + " W",
                "按建议日期之前最近一次 FTP 换算各段功率。FTP 高低不抵消恢复异常。");
    }

    private Factor weightFactor(LocalDate date, DailyCheckin row) {
        LocalDate day = row == null ? null : row.getCalendarDate();
        if (!fresh(date, day, WEIGHT_MAX_AGE_DAYS) || row.getWeightKg() == null || row.getWeightKg().signum() <= 0) {
            return missing("weight", "体重", false, day, "需要近 14 天的有效体重以计算 W/kg；缺失不影响按 FTP 百分比训练。");
        }
        return new Factor("weight", "体重", INFO, false, true, day, row.getWeightKg().stripTrailingZeros().toPlainString() + " kg",
                "与有效 FTP 一起计算功体比；单次体重变化不作为疲劳或脱水结论。");
    }

    private Prescription prescription(Light light, int available, Integer ftp, TrainingStatus training) {
        if (light == RED || available == 0) {
            return new Prescription("REST", light == RED ? "恢复日" : "先补齐状态，再决定训练", 0,
                    "不安排骑行课", "留出恢复时间；下一次训练前重新评估。", List.of(), "今天不补做间歇，明日同步并打卡后再评估。");
        }
        if (light == YELLOW) {
            return new Prescription("RECOVERY", "20 分钟轻松恢复骑（可改休息）", 20, "Z1 · 40–50% FTP",
                    "保留轻松活动，避免继续堆积疲劳。", List.of(
                    step("轻松转腿", 5, 40, 45, ftp, "腿部几乎不费力，可自如交谈"),
                    step("恢复骑", 10, 45, 50, ftp, "体感用力不超过 2/10"),
                    step("放松", 5, 40, 45, ftp, "放松踩踏")),
                    "若热身仍明显疲劳，直接结束并休息；缺数据时先补齐再决定是否骑行。");
        }
        boolean lowAerobic = training != null && "AEROBIC_LOW_SHORTAGE".equals(training.getBalanceFeedbackPhrase());
        int mainMinutes = lowAerobic ? 45 : 30;
        return new Prescription("ENDURANCE", lowAerobic ? "60 分钟基础有氧" : "45 分钟稳定有氧", mainMinutes + 15,
                "主段 Z2 · 60–70% FTP", lowAerobic ? "恢复信号允许，优先补足低强度有氧。" : "维持有氧基础；绿灯不自动追加间歇课。",
                List.of(step("热身", 10, 45, 55, ftp, "逐渐进入节奏"),
                        step("稳定有氧", mainMinutes, 60, 70, ftp, "体感用力约 2–3/10，能完整交谈"),
                        step("放松", 5, 40, 50, ftp, "轻松踩踏")),
                "热身后若体感明显比平时吃力，改为恢复骑或提前结束；不要为追瓦数硬撑。");
    }

    private Step step(String name, int minutes, int low, int high, Integer ftp, String effort) {
        return new Step(name, minutes, low, high, ftp == null ? null : (int) Math.round(ftp * low / 100.0),
                ftp == null ? null : (int) Math.round(ftp * high / 100.0), effort);
    }

    private Factor missing(String key, String label, boolean recovery, LocalDate day, String reason) {
        return new Factor(key, label, UNKNOWN, recovery, false, day, "数据待补齐", reason);
    }

    private boolean fresh(LocalDate date, LocalDate day, int maxAge) {
        return day != null && !day.isAfter(date) && ChronoUnit.DAYS.between(day, date) <= maxAge;
    }

    private boolean positive(Double value) { return value != null && Double.isFinite(value) && value > 0; }

    private String number(Double value) { return positive(value) ? String.format(Locale.ROOT, "%.1f", value) : "—"; }

    private <T> T latest(List<T> rows, Function<T, LocalDate> dateOf, LocalDate date) {
        return rows.stream().filter(r -> dateOf.apply(r) != null && !dateOf.apply(r).isAfter(date))
                .max(Comparator.comparing(dateOf)).orElse(null);
    }
}
