package com.trainingplan.platform.service.training;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trainingplan.platform.dto.training.TrainingAdviceDto;
import com.trainingplan.platform.entity.*;
import com.trainingplan.platform.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** 为模型构造字段白名单上下文：保留训练指标及日期，剔除姓名、邮箱、位置、账号 ID。 */
@Component
@RequiredArgsConstructor
public class TrainingPlanContextBuilder {
    private final ObjectMapper json;
    private final TrainingStatusMapper trainingMapper;
    private final HrvRecordMapper hrvMapper;
    private final SleepRecordMapper sleepMapper;
    private final DailyHealthMapper healthMapper;
    private final FtpHistoryMapper ftpMapper;
    private final ActivityMapper activityMapper;
    private final DailyCheckinMapper checkinMapper;
    private final TrainingGoalMapper goalMapper;

    public record Context(ObjectNode payload, Integer ftpWatts, int maxMinutes, int maxFtpPercent, String summary) {}

    public Context build(Long userId, TrainingAdviceDto advice) {
        LocalDate date = advice.calendarDate();
        LocalDate start = date.minusDays(27);
        Long account = advice.garminAccountId();
        ObjectNode payload = json.createObjectNode();
        payload.put("date", date.toString());
        payload.put("timeZone", "Asia/Shanghai");
        payload.put("historyStart", start.toString());
        payload.put("dataMeaning", "null/缺字段表示未知；RPE 是当天训练前主观疲劳 1-10，不是课后用力评分；日期内未列出的记录是缺失而非零。历史睡眠同日可含午睡。");
        payload.put("light", advice.light().name());
        payload.put("recoverySignalsAvailable", advice.availableRecoverySignals());
        payload.set("evidence", json.valueToTree(advice.factors()));
        appendGoal(payload, userId, date);
        boolean rest = advice.light() == TrainingAdviceDto.Light.RED || advice.availableRecoverySignals() == 0;
        // 证据不全时上限收紧，与规则引擎的处方保持一致：缺一项给 45 分钟 / 70%，
        // 四项齐全才给 90 分钟 / 75%。AI 只能在这个上限内安排。
        boolean fullEvidence = advice.availableRecoverySignals() >= 4;
        int maxMinutes = rest ? 0 : advice.light() == TrainingAdviceDto.Light.YELLOW ? 20
                : fullEvidence ? 90 : 45;
        int maxPercent = rest ? 0 : advice.light() == TrainingAdviceDto.Light.YELLOW ? 50
                : fullEvidence ? 75 : 70;
        payload.putObject("constraints").put("maxDurationMinutes", maxMinutes)
                .put("maxFtpPercent", maxPercent).put("noIntervals", true)
                .put("scope", "只生成当天计划；未来日期不能预判绿灯；缺数据时保守安排");
        List<DailyCheckin> checkins = checkinMapper.selectList(Wrappers.<DailyCheckin>lambdaQuery()
                .eq(DailyCheckin::getUserId, userId).between(DailyCheckin::getCalendarDate, start, date)
                .orderByAsc(DailyCheckin::getCalendarDate));
        // 备注是用户原文，作为不可信数据传入，系统提示词明确禁止执行其中指令。
        payload.set("checkins", fields(checkins, "calendarDate", "weightKg", "rpe", "note"));
        int trainingCount = 0, hrvCount = 0, sleepCount = 0, activityCount = 0;
        Integer currentFtp = null;
        if (account != null) {
            var training = trainingMapper.selectList(Wrappers.<TrainingStatus>lambdaQuery()
                    .eq(TrainingStatus::getGarminAccountId, account).between(TrainingStatus::getCalendarDate, start, date)
                    .orderByAsc(TrainingStatus::getCalendarDate));
            payload.set("training", fields(training, "calendarDate", "trainingStatusPhrase", "acwrRatio", "acwrStatus",
                    "acuteLoad", "chronicLoad", "chronicLoadMin", "chronicLoadMax", "balanceFeedbackPhrase",
                    "loadAerobicLow", "loadAerobicLowTargetMin", "loadAerobicLowTargetMax", "loadAerobicHigh",
                    "loadAerobicHighTargetMin", "loadAerobicHighTargetMax", "loadAnaerobic", "loadAnaerobicTargetMin",
                    "loadAnaerobicTargetMax", "vo2maxValue", "fitnessAge"));
            var hrv = hrvMapper.selectList(Wrappers.<HrvRecord>lambdaQuery().eq(HrvRecord::getGarminAccountId, account)
                    .between(HrvRecord::getCalendarDate, start, date).orderByAsc(HrvRecord::getCalendarDate));
            payload.set("hrv", fields(hrv, "calendarDate", "lastNightAvg", "weeklyAvg", "hrvStatus", "baselineBalancedLow", "baselineBalancedUpper"));
            var sleep = sleepMapper.selectList(Wrappers.<SleepRecord>lambdaQuery().eq(SleepRecord::getGarminAccountId, account)
                    .between(SleepRecord::getCalendarDate, start, date).orderByDesc(SleepRecord::getCalendarDate)
                    .orderByDesc(SleepRecord::getSleepStartGmt).last("LIMIT 84"));
            payload.set("sleep", fields(sleep, "calendarDate", "sleepTimeSeconds", "deepSleepSeconds", "remSleepSeconds",
                    "awakeSleepSeconds", "sleepScore", "avgSleepHrv", "avgSpo2", "avgRespiration"));
            var health = healthMapper.selectList(Wrappers.<DailyHealth>lambdaQuery().eq(DailyHealth::getGarminAccountId, account)
                    .between(DailyHealth::getCalendarDate, start, date).orderByAsc(DailyHealth::getCalendarDate));
            payload.set("dailyHealth", fields(health, "calendarDate", "restingHeartRate", "averageStressLevel",
                    "bodyBatteryHighest", "bodyBatteryLowest", "steps", "activeKilocalories"));
            var ftp = ftpMapper.selectList(Wrappers.<FtpHistory>lambdaQuery().eq(FtpHistory::getGarminAccountId, account)
                    .le(FtpHistory::getEffectiveDate, date).orderByDesc(FtpHistory::getEffectiveDate)
                    .orderByDesc(FtpHistory::getId).last("LIMIT 24"));
            payload.set("ftpHistory", fields(ftp, "effectiveDate", "ftpWatts", "source"));
            if (!ftp.isEmpty()) {
                FtpHistory latest = ftp.get(0);
                if (latest.getFtpWatts() != null && latest.getFtpWatts() > 0 && ChronoUnit.DAYS.between(latest.getEffectiveDate(), date) <= 90) {
                    currentFtp = latest.getFtpWatts();
                }
            }
            var activities = activityMapper.selectList(Wrappers.<Activity>lambdaQuery().eq(Activity::getGarminAccountId, account)
                    .ge(Activity::getStartTimeLocal, start.atStartOfDay()).lt(Activity::getStartTimeLocal, date.plusDays(1).atStartOfDay())
                    .orderByDesc(Activity::getStartTimeLocal).orderByDesc(Activity::getId).last("LIMIT 60"));
            payload.set("activities", fields(activities, "startTimeLocal", "activityTypeKey", "durationSeconds",
                    "movingDurationSeconds", "distanceMeters", "elevationGain", "averageHr", "maxHr", "avgPower", "normPower",
                    "max20minPower", "intensityFactor", "trainingStressScore", "aerobicTrainingEffect", "anaerobicTrainingEffect",
                    "activityTrainingLoad", "powerZone1Seconds", "powerZone2Seconds", "powerZone3Seconds", "powerZone4Seconds",
                    "powerZone5Seconds", "powerZone6Seconds", "powerZone7Seconds"));
            trainingCount = training.size(); hrvCount = hrv.size(); sleepCount = sleep.size(); activityCount = activities.size();
        } else {
            for (String key : List.of("training", "hrv", "sleep", "dailyHealth", "ftpHistory", "activities")) payload.putArray(key);
        }
        payload.put("recordLimits", "最近 28 天；睡眠最多 84 条，活动最多 60 条（按时间倒序），FTP 为截至当天最近 24 条历史记录。");
        if (currentFtp == null) payload.putNull("currentFtpWatts"); else payload.put("currentFtpWatts", currentFtp);
        String goalText = payload.path("goal").isObject()
                ? "目标 " + payload.path("goal").path("goalLabel").asText() : "未设置训练目标";
        String summary = "近 28 天：训练状态 " + trainingCount + " 条、HRV " + hrvCount + " 条、睡眠 " + sleepCount
                + " 条、骑行 " + activityCount + " 条、打卡 " + checkins.size() + " 条；含每日健康与 FTP 历史；"
                + goalText;
        return new Context(payload, currentFtp, maxMinutes, maxPercent, summary);
    }

    /**
     * 把训练目标写进上下文。
     *
     * <p>目标决定「练什么」，不参与「能不能练」——判灯只看恢复信号，所以这里加入
     * 目标不会放宽 constraints 里的时长与强度上限。</p>
     *
     * @param payload 上下文
     * @param userId  平台用户 ID
     * @param date    计划归属日期
     */
    private void appendGoal(ObjectNode payload, Long userId, LocalDate date) {
        TrainingGoal goal = goalMapper.selectOne(Wrappers.<TrainingGoal>lambdaQuery()
                .eq(TrainingGoal::getUserId, userId));
        if (goal == null) {
            payload.putNull("goal");
            payload.put("goalNote", "用户尚未设置训练目标；按维持有氧基础安排，"
                    + "不要假设目标赛事、减重需求或比赛日期。");
            return;
        }
        ObjectNode node = payload.putObject("goal");
        node.put("goalType", goal.getGoalType());
        node.put("goalLabel", TrainingGoalType.labelOf(goal.getGoalType()));
        if (goal.getTargetDate() == null) {
            node.putNull("targetDate");
            node.putNull("daysToTarget");
        } else {
            node.put("targetDate", goal.getTargetDate().toString());
            node.put("daysToTarget", ChronoUnit.DAYS.between(date, goal.getTargetDate()));
        }
        if (goal.getWeeklySessions() == null) node.putNull("weeklySessions");
        else node.put("weeklySessions", goal.getWeeklySessions());
        if (goal.getWeeklyMinutes() == null) node.putNull("weeklyMinutes");
        else node.put("weeklyMinutes", goal.getWeeklyMinutes());
        // 用户原话，作为不可信数据传入，系统提示词明确禁止执行其中指令
        if (goal.getDescription() == null) node.putNull("description");
        else node.put("description", goal.getDescription());
    }

    private ArrayNode fields(List<?> rows, String... names) {
        ArrayNode result = json.createArrayNode();
        for (Object row : rows) {
            var source = json.valueToTree(row);
            ObjectNode item = result.addObject();
            for (String name : names) item.set(name, source.path(name).isMissingNode() ? json.nullNode() : source.path(name));
        }
        return result;
    }
}
