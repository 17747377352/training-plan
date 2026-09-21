package com.trainingplan.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.sync.DailyHealthDto;
import com.trainingplan.platform.dto.sync.HrvRecordDto;
import com.trainingplan.platform.dto.sync.SleepRecordDto;
import com.trainingplan.platform.dto.sync.SyncIngestRequest;
import com.trainingplan.platform.dto.sync.SyncTaskPayload;
import com.trainingplan.platform.entity.DailyHealth;
import com.trainingplan.platform.entity.GarminAccount;
import com.trainingplan.platform.entity.HrvRecord;
import com.trainingplan.platform.entity.SleepRecord;
import com.trainingplan.platform.entity.SyncJob;
import com.trainingplan.platform.mapper.DailyHealthMapper;
import com.trainingplan.platform.mapper.GarminAccountMapper;
import com.trainingplan.platform.mapper.HrvRecordMapper;
import com.trainingplan.platform.mapper.SleepRecordMapper;
import com.trainingplan.platform.mapper.SyncJobMapper;
import com.trainingplan.platform.security.TokenCipher;
import com.trainingplan.platform.service.SyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Garmin 数据同步服务实现。
 *
 * <p>任务经 Redis 队列交给采集器执行，采集器再通过内部接口回传数据；
 * 平台负责去重、覆盖更新与状态流转。</p>
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncServiceImpl implements SyncService {

    private static final String JOB_TYPE_MANUAL = "MANUAL";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final int MAX_DAYS = 365;

    private final SyncJobMapper syncJobMapper;
    private final GarminAccountMapper accountMapper;
    private final DailyHealthMapper dailyHealthMapper;
    private final SleepRecordMapper sleepRecordMapper;
    private final HrvRecordMapper hrvRecordMapper;
    private final TokenCipher tokenCipher;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.sync.task-queue:training-plan:sync:jobs}")
    private String taskQueue;

    /** 从未被采集器取走的任务，超过该时长即判定僵死。 */
    @Value("${app.sync.pending-timeout:10m}")
    private Duration pendingTimeout;

    /** 已被取走但长期未完成的任务（采集器进程可能已消失）。 */
    @Value("${app.sync.running-timeout:30m}")
    private Duration runningTimeout;

    @Override
    public Long triggerSync(Long userId, Long accountId, Integer days) {
        GarminAccount account = accountMapper.selectById(accountId);
        if (account == null || !account.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Garmin账号不存在");
        }
        if (account.getTokenCiphertext() == null || account.getTokenCiphertext().isBlank()) {
            throw new BusinessException(ErrorCode.GARMIN_AUTH_REQUIRED, "Garmin账号尚未完成绑定");
        }
        int range = (days == null || days < 1) ? 1 : Math.min(days, MAX_DAYS);

        SyncJob job = new SyncJob();
        job.setGarminAccountId(accountId);
        job.setJobType(JOB_TYPE_MANUAL);
        job.setJobStatus(STATUS_PENDING);
        job.setRequestedBy(userId);
        syncJobMapper.insert(job);

        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(range - 1L);
        SyncTaskPayload payload = new SyncTaskPayload(
                job.getId(), accountId, account.getRegion(), start.toString(), end.toString());
        try {
            redisTemplate.opsForList().leftPush(taskQueue, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException exception) {
            syncJobMapper.updateById(failUpdate(job.getId(), ErrorCode.SYSTEM_ERROR.name()));
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "同步任务投递失败");
        }
        log.info("已投递同步任务 jobId={} accountId={} 区间={}~{}", job.getId(), accountId, start, end);
        return job.getId();
    }

    @Override
    public String loadTokenForJob(Long jobId) {
        SyncJob job = requireJob(jobId);
        GarminAccount account = accountMapper.selectById(job.getGarminAccountId());
        if (account == null || account.getTokenCiphertext() == null) {
            throw new BusinessException(ErrorCode.GARMIN_AUTH_REQUIRED, "Garmin账号令牌缺失");
        }
        return tokenCipher.decrypt(account.getId(), account.getTokenCiphertext());
    }

    @Override
    public String regionForJob(Long jobId) {
        SyncJob job = requireJob(jobId);
        GarminAccount account = accountMapper.selectById(job.getGarminAccountId());
        if (account == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Garmin账号不存在");
        }
        return account.getRegion();
    }

    @Override
    public void markRunning(Long jobId) {
        requireJob(jobId);
        SyncJob update = new SyncJob();
        update.setId(jobId);
        update.setJobStatus(STATUS_RUNNING);
        update.setStartedTime(LocalDateTime.now());
        syncJobMapper.updateById(update);
    }

    @Override
    public void ingest(Long jobId, SyncIngestRequest request) {
        SyncJob job = requireJob(jobId);
        Long accountId = job.getGarminAccountId();
        int daily = upsertDailyHealth(accountId, request.dailyHealth());
        int sleep = upsertSleep(accountId, request.sleep());
        int hrv = upsertHrv(accountId, request.hrv());
        log.info("同步数据已入库 jobId={} daily={} sleep={} hrv={}", jobId, daily, sleep, hrv);
    }

    @Override
    public void complete(Long jobId) {
        SyncJob job = requireJob(jobId);
        SyncJob update = new SyncJob();
        update.setId(jobId);
        update.setJobStatus(STATUS_SUCCESS);
        update.setFinishedTime(LocalDateTime.now());
        syncJobMapper.updateById(update);

        GarminAccount account = new GarminAccount();
        account.setId(job.getGarminAccountId());
        account.setLastSyncTime(LocalDateTime.now());
        accountMapper.updateById(account);
        log.info("同步任务完成 jobId={}", jobId);
    }

    @Override
    public void fail(Long jobId, String errorCode) {
        syncJobMapper.updateById(failUpdate(jobId, errorCode));
        log.info("同步任务失败 jobId={} errorCode={}", jobId, errorCode);
    }

    @Override
    public int failStaleJobs() {
        LocalDateTime now = LocalDateTime.now();
        int affected = 0;
        affected += failStale(STATUS_PENDING, SyncJob::getCreateTime,
                now.minus(pendingTimeout));
        affected += failStale(STATUS_RUNNING, SyncJob::getStartedTime,
                now.minus(runningTimeout));
        return affected;
    }

    /**
     * 收敛某一状态下超时未推进的任务。
     *
     * @param status  任务状态
     * @param column  用于判定超时的时间列
     * @param before  早于该时间视为僵死
     * @return 影响行数
     */
    private int failStale(String status, SFunction<SyncJob, ?> column, LocalDateTime before) {
        SyncJob update = new SyncJob();
        update.setJobStatus(STATUS_FAILED);
        update.setFinishedTime(LocalDateTime.now());
        update.setErrorCode(ErrorCode.SYNC_JOB_TIMEOUT.name());
        LambdaQueryWrapper<SyncJob> wrapper = Wrappers.<SyncJob>lambdaQuery()
                .eq(SyncJob::getJobStatus, status)
                .lt(column, before);
        return syncJobMapper.update(update, wrapper);
    }

    private SyncJob failUpdate(Long jobId, String errorCode) {
        SyncJob update = new SyncJob();
        update.setId(jobId);
        update.setJobStatus(STATUS_FAILED);
        update.setFinishedTime(LocalDateTime.now());
        update.setErrorCode(errorCode);
        return update;
    }

    private SyncJob requireJob(Long jobId) {
        SyncJob job = syncJobMapper.selectById(jobId);
        if (job == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "同步任务不存在");
        }
        return job;
    }

    private int upsertDailyHealth(Long accountId, List<DailyHealthDto> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int affected = 0;
        for (DailyHealthDto dto : rows) {
            LocalDate date = parseDate(dto.calendarDate());
            if (date == null) {
                continue;
            }
            DailyHealth existing = dailyHealthMapper.selectOne(Wrappers.<DailyHealth>lambdaQuery()
                    .eq(DailyHealth::getGarminAccountId, accountId)
                    .eq(DailyHealth::getCalendarDate, date));
            DailyHealth entity = existing == null ? new DailyHealth() : existing;
            entity.setGarminAccountId(accountId);
            entity.setCalendarDate(date);
            entity.setSteps(dto.steps());
            entity.setDistanceMeters(dto.distanceMeters());
            entity.setTotalKilocalories(dto.totalKilocalories());
            entity.setActiveKilocalories(dto.activeKilocalories());
            entity.setRestingHeartRate(dto.restingHeartRate());
            entity.setMinHeartRate(dto.minHeartRate());
            entity.setMaxHeartRate(dto.maxHeartRate());
            entity.setAverageStressLevel(dto.averageStressLevel());
            entity.setBodyBatteryHighest(dto.bodyBatteryHighest());
            entity.setBodyBatteryLowest(dto.bodyBatteryLowest());
            if (existing == null) {
                dailyHealthMapper.insert(entity);
            } else {
                dailyHealthMapper.updateById(entity);
            }
            affected++;
        }
        return affected;
    }

    private int upsertSleep(Long accountId, List<SleepRecordDto> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int affected = 0;
        for (SleepRecordDto dto : rows) {
            LocalDateTime startGmt = parseDateTime(dto.sleepStartGmt());
            LocalDate date = parseDate(dto.calendarDate());
            if (startGmt == null || date == null) {
                continue;
            }
            SleepRecord existing = sleepRecordMapper.selectOne(Wrappers.<SleepRecord>lambdaQuery()
                    .eq(SleepRecord::getGarminAccountId, accountId)
                    .eq(SleepRecord::getSleepStartGmt, startGmt));
            SleepRecord entity = existing == null ? new SleepRecord() : existing;
            entity.setGarminAccountId(accountId);
            entity.setCalendarDate(date);
            entity.setSleepStartGmt(startGmt);
            entity.setSleepEndGmt(parseDateTime(dto.sleepEndGmt()));
            entity.setSleepTimeSeconds(dto.sleepTimeSeconds());
            entity.setDeepSleepSeconds(dto.deepSleepSeconds());
            entity.setLightSleepSeconds(dto.lightSleepSeconds());
            entity.setRemSleepSeconds(dto.remSleepSeconds());
            entity.setAwakeSleepSeconds(dto.awakeSleepSeconds());
            entity.setSleepScore(dto.sleepScore());
            entity.setAvgSleepHrv(dto.avgSleepHrv());
            entity.setAvgSpo2(dto.avgSpo2());
            entity.setAvgRespiration(dto.avgRespiration());
            if (existing == null) {
                sleepRecordMapper.insert(entity);
            } else {
                sleepRecordMapper.updateById(entity);
            }
            affected++;
        }
        return affected;
    }

    private int upsertHrv(Long accountId, List<HrvRecordDto> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int affected = 0;
        for (HrvRecordDto dto : rows) {
            LocalDate date = parseDate(dto.calendarDate());
            if (date == null) {
                continue;
            }
            HrvRecord existing = hrvRecordMapper.selectOne(Wrappers.<HrvRecord>lambdaQuery()
                    .eq(HrvRecord::getGarminAccountId, accountId)
                    .eq(HrvRecord::getCalendarDate, date));
            HrvRecord entity = existing == null ? new HrvRecord() : existing;
            entity.setGarminAccountId(accountId);
            entity.setCalendarDate(date);
            entity.setLastNightAvg(dto.lastNightAvg());
            entity.setWeeklyAvg(dto.weeklyAvg());
            entity.setHrvStatus(dto.status());
            entity.setBaselineLowUpper(dto.baselineLowUpper());
            entity.setBaselineBalancedLow(dto.baselineBalancedLow());
            entity.setBaselineBalancedUpper(dto.baselineBalancedUpper());
            if (existing == null) {
                hrvRecordMapper.insert(entity);
            } else {
                hrvRecordMapper.updateById(entity);
            }
            affected++;
        }
        return affected;
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            log.warn("采集器上报了无法解析的日期: {}", value);
            return null;
        }
    }

    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            log.warn("采集器上报了无法解析的时间: {}", value);
            return null;
        }
    }
}
