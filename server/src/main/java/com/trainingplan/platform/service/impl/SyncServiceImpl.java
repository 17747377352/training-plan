package com.trainingplan.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainingplan.platform.common.api.PageResult;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.sync.AccountSyncStateDto;
import com.trainingplan.platform.dto.sync.ActivityDto;
import com.trainingplan.platform.dto.sync.DailyHealthDto;
import com.trainingplan.platform.dto.sync.HrvRecordDto;
import com.trainingplan.platform.dto.sync.SleepRecordDto;
import com.trainingplan.platform.dto.sync.SyncIngestRequest;
import com.trainingplan.platform.dto.sync.SyncJobDto;
import com.trainingplan.platform.dto.sync.SyncJobQuery;
import com.trainingplan.platform.dto.sync.SyncOverviewDto;
import com.trainingplan.platform.dto.sync.SyncTaskPayload;
import com.trainingplan.platform.entity.Activity;
import com.trainingplan.platform.entity.DailyHealth;
import com.trainingplan.platform.entity.GarminAccount;
import com.trainingplan.platform.entity.HrvRecord;
import com.trainingplan.platform.entity.SleepRecord;
import com.trainingplan.platform.entity.SyncJob;
import com.trainingplan.platform.mapper.ActivityMapper;
import com.trainingplan.platform.mapper.DailyHealthMapper;
import com.trainingplan.platform.mapper.GarminAccountMapper;
import com.trainingplan.platform.mapper.HrvRecordMapper;
import com.trainingplan.platform.mapper.SleepRecordMapper;
import com.trainingplan.platform.mapper.SyncJobMapper;
import com.trainingplan.platform.security.TokenCipher;
import com.trainingplan.platform.service.SyncService;
import com.trainingplan.platform.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private static final String JOB_TYPE_SCHEDULED = "SCHEDULED";
    private static final String STATUS_ACTIVE_ACCOUNT = "ACTIVE";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final int MAX_DAYS = 365;

    /** 看板统计「最近 N 天失败数」的窗口。 */
    private static final int FAILURE_WINDOW_DAYS = 7;

    /** 未知错误码最长保留的字符数，避免任意长文本落库。 */
    private static final int MAX_ERROR_CODE_LENGTH = 64;

    private final SyncJobMapper syncJobMapper;
    private final GarminAccountMapper accountMapper;
    private final ActivityMapper activityMapper;
    private final DailyHealthMapper dailyHealthMapper;
    private final SleepRecordMapper sleepRecordMapper;
    private final HrvRecordMapper hrvRecordMapper;
    private final TokenCipher tokenCipher;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final UserService userService;

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
        return createJob(account, userId, JOB_TYPE_MANUAL, days);
    }

    @Override
    public Long triggerScheduledSync(Long accountId, Integer days) {
        GarminAccount account = accountMapper.selectById(accountId);
        if (account == null) {
            return null;
        }
        return createJob(account, null, JOB_TYPE_SCHEDULED, days);
    }

    @Override
    public int triggerDailySyncs(Integer days) {
        List<GarminAccount> accounts = accountMapper.selectList(Wrappers.<GarminAccount>lambdaQuery()
                .eq(GarminAccount::getAuthStatus, STATUS_ACTIVE_ACCOUNT)
                .eq(GarminAccount::getSyncEnabled, 1));
        int created = 0;
        for (GarminAccount account : accounts) {
            if (hasUnfinishedJob(account.getId()) || account.getTokenCiphertext() == null
                    || account.getTokenCiphertext().isBlank()) {
                continue;
            }
            try {
                if (createJob(account, null, JOB_TYPE_SCHEDULED, days) != null) {
                    created++;
                }
            } catch (BusinessException exception) {
                log.warn("定时同步跳过账号 accountId={} 原因={}", account.getId(), exception.getMessage());
            }
        }
        log.info("定时同步已为 {} 个账号创建任务，候选账号 {} 个", created, accounts.size());
        return created;
    }

    /**
     * 校验账号可同步并创建任务、投递队列。
     *
     * @param account     账号
     * @param requestedBy 发起用户，定时任务传 null
     * @param jobType     任务类型
     * @param days        回溯天数
     * @return 任务 ID
     */
    private Long createJob(GarminAccount account, Long requestedBy, String jobType, Integer days) {
        int range = (days == null || days < 1) ? 1 : Math.min(days, MAX_DAYS);
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(range - 1L);
        return createJob(account, requestedBy, jobType, start, end, null);
    }

    /**
     * 按明确的数据区间创建任务并投递队列。
     *
     * <p>区间落地到 {@code sync_job}，看板据此展示「这次拉了哪段数据」，
     * 重试也据此复现原区间。</p>
     *
     * @param account      账号
     * @param requestedBy  发起用户，定时任务传 null
     * @param jobType      任务类型
     * @param start        数据起始日期（含）
     * @param end          数据结束日期（含）
     * @param retryOfJobId 重试来源任务 ID，非重试传 null
     * @return 任务 ID
     */
    private Long createJob(GarminAccount account, Long requestedBy, String jobType,
                           LocalDate start, LocalDate end, Long retryOfJobId) {
        Long accountId = account.getId();
        if (account.getTokenCiphertext() == null || account.getTokenCiphertext().isBlank()) {
            throw new BusinessException(ErrorCode.GARMIN_AUTH_REQUIRED, "Garmin账号尚未完成绑定");
        }

        SyncJob job = new SyncJob();
        job.setGarminAccountId(accountId);
        job.setJobType(jobType);
        job.setJobStatus(STATUS_PENDING);
        job.setStartDate(start);
        job.setEndDate(end);
        job.setRequestedBy(requestedBy);
        job.setRetryOfJobId(retryOfJobId);
        syncJobMapper.insert(job);

        SyncTaskPayload payload = new SyncTaskPayload(
                job.getId(), accountId, account.getRegion(), start.toString(), end.toString());
        try {
            redisTemplate.opsForList().leftPush(taskQueue, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException exception) {
            // 任务刚插入、仍是 PENDING，走同一套失败收敛逻辑，避免两处各写一遍状态
            fail(job.getId(), ErrorCode.SYSTEM_ERROR.name());
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
        // 只允许 PENDING → RUNNING。任务可能已经被僵死清理器判超时，
        // 这时队列里那条迟到的消息必须作废，否则会把终态任务重新推进。
        int affected = syncJobMapper.update(null, Wrappers.<SyncJob>lambdaUpdate()
                .eq(SyncJob::getId, jobId)
                .eq(SyncJob::getJobStatus, STATUS_PENDING)
                .set(SyncJob::getJobStatus, STATUS_RUNNING)
                .set(SyncJob::getStartedTime, LocalDateTime.now()));
        if (affected == 0) {
            log.warn("同步任务已不在排队中，本次执行作废 jobId={}", jobId);
            throw new BusinessException(ErrorCode.NOT_FOUND, "同步任务已结束，本次执行作废");
        }
    }

    @Override
    public void ingest(Long jobId, SyncIngestRequest request) {
        SyncJob job = requireJob(jobId);
        Long accountId = job.getGarminAccountId();
        int daily = upsertDailyHealth(accountId, request.dailyHealth());
        int sleep = upsertSleep(accountId, request.sleep());
        int hrv = upsertHrv(accountId, request.hrv());
        int activities = upsertActivities(accountId, request.activities());
        log.info("同步数据已入库 jobId={} daily={} sleep={} hrv={} activity={}",
                jobId, daily, sleep, hrv, activities);
    }

    @Override
    public void complete(Long jobId) {
        SyncJob job = requireJob(jobId);
        // 只允许进行中的任务转成成功。同时显式清空错误列，避免出现
        // 「成功但带着失败原因」的自相矛盾记录——MyBatis-Plus 的 updateById
        // 会忽略 null 字段，必须用 set 显式置空。
        int affected = syncJobMapper.update(null, Wrappers.<SyncJob>lambdaUpdate()
                .eq(SyncJob::getId, jobId)
                .in(SyncJob::getJobStatus, List.of(STATUS_PENDING, STATUS_RUNNING))
                .set(SyncJob::getJobStatus, STATUS_SUCCESS)
                .set(SyncJob::getFinishedTime, LocalDateTime.now())
                .set(SyncJob::getErrorCode, null)
                .set(SyncJob::getErrorMessage, null));
        if (affected == 0) {
            log.warn("同步任务已结束，忽略迟到的完成上报 jobId={}", jobId);
            return;
        }

        GarminAccount account = new GarminAccount();
        account.setId(job.getGarminAccountId());
        account.setLastSyncTime(LocalDateTime.now());
        accountMapper.updateById(account);
        log.info("同步任务完成 jobId={}", jobId);
    }

    @Override
    public void fail(Long jobId, String errorCode) {
        int affected = syncJobMapper.update(null, Wrappers.<SyncJob>lambdaUpdate()
                .eq(SyncJob::getId, jobId)
                .in(SyncJob::getJobStatus, List.of(STATUS_PENDING, STATUS_RUNNING))
                .set(SyncJob::getJobStatus, STATUS_FAILED)
                .set(SyncJob::getFinishedTime, LocalDateTime.now())
                .set(SyncJob::getErrorCode, errorCode)
                .set(SyncJob::getErrorMessage, describeError(errorCode)));
        if (affected == 0) {
            // 已成功的任务不能被迟到的失败上报翻成失败，否则看板上的「上次成功」会消失
            log.info("同步任务已结束，忽略迟到的失败上报 jobId={} errorCode={}", jobId, errorCode);
            return;
        }
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
        update.setErrorMessage(ErrorCode.SYNC_JOB_TIMEOUT.getMessage());
        LambdaQueryWrapper<SyncJob> wrapper = Wrappers.<SyncJob>lambdaQuery()
                .eq(SyncJob::getJobStatus, status)
                .lt(column, before);
        return syncJobMapper.update(update, wrapper);
    }

    @Override
    public PageResult<SyncJobDto> listJobs(Long userId, SyncJobQuery query) {
        userService.getProfile(userId);
        Map<Long, GarminAccount> accounts = accountsOf(userId);
        List<Long> accountIds = scopeAccountIds(accounts, query.getGarminAccountId());
        if (accountIds.isEmpty()) {
            return new PageResult<>(0L, query.currentPage(), query.pageSize(), List.of());
        }

        LambdaQueryWrapper<SyncJob> wrapper = Wrappers.<SyncJob>lambdaQuery()
                .in(SyncJob::getGarminAccountId, accountIds)
                .eq(StringUtils.hasText(query.getJobStatus()), SyncJob::getJobStatus, query.getJobStatus())
                .eq(StringUtils.hasText(query.getJobType()), SyncJob::getJobType, query.getJobType())
                .orderByDesc(SyncJob::getCreateTime)
                .orderByDesc(SyncJob::getId);

        IPage<SyncJob> page = syncJobMapper.selectPage(
                Page.of(query.currentPage(), query.pageSize()), wrapper);
        List<SyncJobDto> records = page.getRecords().stream()
                .map(job -> toJobDto(job, accounts))
                .toList();
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), records);
    }

    @Override
    public SyncOverviewDto overview(Long userId) {
        userService.getProfile(userId);
        Map<Long, GarminAccount> accounts = accountsOf(userId);
        if (accounts.isEmpty()) {
            return new SyncOverviewDto(null, null, 0L, 0L, false, List.of());
        }

        List<AccountSyncStateDto> states = new ArrayList<>();
        LocalDateTime lastSuccess = null;
        LocalDateTime lastFailure = null;
        long unfinished = 0L;
        long failure7d = 0L;
        LocalDateTime failureWindowStart = LocalDateTime.now().minusDays(FAILURE_WINDOW_DAYS);

        // 账号数量是个位数，逐账号取最近任务比一次拉全表再分组更清楚，
        // 且走 idx_garmin_account_id_create_time。
        for (GarminAccount account : accounts.values()) {
            SyncJob latest = latestJob(account.getId(), null);
            SyncJob latestSuccess = latestJob(account.getId(), STATUS_SUCCESS);
            SyncJob latestFailure = latestJob(account.getId(), STATUS_FAILED);

            lastSuccess = later(lastSuccess, latestSuccess == null ? null : latestSuccess.getFinishedTime());
            lastFailure = later(lastFailure, latestFailure == null ? null : latestFailure.getFinishedTime());
            unfinished += countJobs(account.getId(), List.of(STATUS_PENDING, STATUS_RUNNING), null);
            failure7d += countJobs(account.getId(), List.of(STATUS_FAILED), failureWindowStart);

            states.add(new AccountSyncStateDto(
                    account.getId(),
                    accountLabel(account),
                    account.getAuthStatus(),
                    account.getSyncEnabled(),
                    account.getLastSyncTime(),
                    latestSuccess == null ? null : latestSuccess.getFinishedTime(),
                    latestFailure == null ? null : latestFailure.getFinishedTime(),
                    latestFailure == null ? null : latestFailure.getErrorMessage(),
                    latest == null ? null : latest.getStartDate(),
                    latest == null ? null : latest.getEndDate()));
        }
        return new SyncOverviewDto(lastSuccess, lastFailure, unfinished, failure7d, true, states);
    }

    @Override
    public Long retryJob(Long userId, Long jobId) {
        userService.getProfile(userId);
        Map<Long, GarminAccount> accounts = accountsOf(userId);
        SyncJob job = syncJobMapper.selectById(jobId);
        if (job == null || !accounts.containsKey(job.getGarminAccountId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "同步任务不存在");
        }
        GarminAccount account = accountMapper.selectById(job.getGarminAccountId());
        if (account == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Garmin账号不存在");
        }
        if (hasUnfinishedJob(account.getId())) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "该账号已有同步任务在执行中");
        }

        // 复现原任务的区间，而不是「从今天往回推同样的天数」——后者在
        // 隔天重试时会漏掉原来那段数据。
        LocalDate start = job.getStartDate();
        LocalDate end = job.getEndDate();
        if (start == null || end == null) {
            // V5 之前创建的任务没有记录区间，退回它自己的创建日单天。
            LocalDate day = job.getCreateTime() == null
                    ? LocalDate.now()
                    : job.getCreateTime().toLocalDate();
            start = day;
            end = day;
        }
        Long retryJobId = createJob(account, userId, JOB_TYPE_MANUAL, start, end, job.getId());
        log.info("同步任务已重试 sourceJobId={} newJobId={} 区间={}~{}", jobId, retryJobId, start, end);
        return retryJobId;
    }

    /**
     * 取当前用户名下的 Garmin 账号，键为账号 ID。
     *
     * @param userId 平台用户 ID
     * @return 账号 ID 到账号的映射
     */
    private Map<Long, GarminAccount> accountsOf(Long userId) {
        Map<Long, GarminAccount> accounts = new LinkedHashMap<>();
        for (GarminAccount account : accountMapper.selectList(Wrappers.<GarminAccount>lambdaQuery()
                .eq(GarminAccount::getUserId, userId)
                .orderByAsc(GarminAccount::getId))) {
            accounts.put(account.getId(), account);
        }
        return accounts;
    }

    /**
     * 把查询条件里的账号 ID 收敛到当前用户自有账号范围内。
     *
     * <p>传了别人的账号 ID 时返回空列表（最终得到空结果），而不是 404——
     * 不泄露该账号是否存在。</p>
     *
     * @param accounts  当前用户自有账号
     * @param requested 请求参数里的账号 ID，可为空
     * @return 生效的账号 ID 列表
     */
    private List<Long> scopeAccountIds(Map<Long, GarminAccount> accounts, Long requested) {
        if (requested == null) {
            return List.copyOf(accounts.keySet());
        }
        return accounts.containsKey(requested) ? List.of(requested) : List.of();
    }

    private SyncJob latestJob(Long accountId, String status) {
        List<SyncJob> jobs = syncJobMapper.selectList(Wrappers.<SyncJob>lambdaQuery()
                .eq(SyncJob::getGarminAccountId, accountId)
                .eq(status != null, SyncJob::getJobStatus, status)
                .orderByDesc(SyncJob::getCreateTime)
                .orderByDesc(SyncJob::getId)
                .last("LIMIT 1"));
        return jobs.isEmpty() ? null : jobs.get(0);
    }

    private long countJobs(Long accountId, List<String> statuses, LocalDateTime createdAfter) {
        Long count = syncJobMapper.selectCount(Wrappers.<SyncJob>lambdaQuery()
                .eq(SyncJob::getGarminAccountId, accountId)
                .in(SyncJob::getJobStatus, statuses)
                .ge(createdAfter != null, SyncJob::getCreateTime, createdAfter));
        return count == null ? 0L : count;
    }

    private LocalDateTime later(LocalDateTime current, LocalDateTime candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    private SyncJobDto toJobDto(SyncJob job, Map<Long, GarminAccount> accounts) {
        Long duration = null;
        if (job.getStartedTime() != null && job.getFinishedTime() != null) {
            duration = Duration.between(job.getStartedTime(), job.getFinishedTime()).toSeconds();
        }
        return new SyncJobDto(
                job.getId(),
                job.getGarminAccountId(),
                accountLabel(accounts.get(job.getGarminAccountId())),
                job.getJobType(),
                job.getJobStatus(),
                job.getStartDate(),
                job.getEndDate(),
                job.getRequestedBy(),
                job.getStartedTime(),
                job.getFinishedTime(),
                duration,
                job.getErrorCode(),
                job.getErrorMessage(),
                job.getRetryOfJobId(),
                job.getCreateTime());
    }

    /**
     * 生成账号展示名：脱敏邮箱 + 站点。
     *
     * @param account 账号，可能为 null
     * @return 展示名
     */
    private String accountLabel(GarminAccount account) {
        if (account == null) {
            return "已解绑账号";
        }
        String email = account.getGarminEmailMasked();
        if (email == null || email.isBlank()) {
            return "Garmin 账号 #" + account.getId();
        }
        return email + ("CN".equals(account.getRegion()) ? " · 中国站" : " · 国际站");
    }

    /**
     * 把错误编码翻译成可展示的中文原因。
     *
     * <p>错误码由采集器经内部接口上报，属于可信来源，但仍按固定枚举解析：
     * 认识的编码取枚举文案，不认识的只保留编码本身并截断，避免把任意长文本
     * 写进任务表再渲染到看板上。</p>
     *
     * @param errorCode 脱敏错误编码
     * @return 可展示的失败原因，入参为空时返回 null
     */
    private String describeError(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            return null;
        }
        try {
            return ErrorCode.valueOf(errorCode).getMessage();
        } catch (IllegalArgumentException exception) {
            String trimmed = errorCode.trim();
            return "同步失败（" + trimmed.substring(0, Math.min(trimmed.length(), MAX_ERROR_CODE_LENGTH)) + "）";
        }
    }

    /**
     * 账号是否已有未完成的同步任务，避免同一账号并发同步。
     *
     * @param accountId Garmin 账号 ID
     * @return 是否存在未完成任务
     */
    private boolean hasUnfinishedJob(Long accountId) {
        Long count = syncJobMapper.selectCount(Wrappers.<SyncJob>lambdaQuery()
                .eq(SyncJob::getGarminAccountId, accountId)
                .in(SyncJob::getJobStatus, List.of(STATUS_PENDING, STATUS_RUNNING)));
        return count != null && count > 0;
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


    /**
     * 覆盖写入骑行活动，按 (账号, Garmin 活动 ID) 去重。
     *
     * @param accountId 账号 ID
     * @param rows      活动列表
     * @return 处理条数
     */
    private int upsertActivities(Long accountId, List<ActivityDto> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int affected = 0;
        for (ActivityDto dto : rows) {
            if (dto.garminActivityId() == null) {
                continue;
            }
            Activity existing = activityMapper.selectOne(Wrappers.<Activity>lambdaQuery()
                    .eq(Activity::getGarminAccountId, accountId)
                    .eq(Activity::getGarminActivityId, dto.garminActivityId()));
            Activity entity = existing == null ? new Activity() : existing;
            entity.setGarminAccountId(accountId);
            entity.setGarminActivityId(dto.garminActivityId());
            entity.setActivityTypeKey(dto.activityTypeKey());
            entity.setActivityTypeId(dto.activityTypeId());
            entity.setParentTypeId(dto.parentTypeId());
            entity.setActivityName(dto.activityName());
            entity.setStartTimeGmt(parseDateTime(dto.startTimeGmt()));
            entity.setStartTimeLocal(parseDateTime(dto.startTimeLocal()));
            entity.setDurationSeconds(toInt(dto.durationSeconds()));
            entity.setMovingDurationSeconds(toInt(dto.movingDurationSeconds()));
            entity.setElapsedDurationSeconds(toInt(dto.elapsedDurationSeconds()));
            entity.setDistanceMeters(dto.distanceMeters());
            entity.setElevationGain(dto.elevationGain());
            entity.setElevationLoss(dto.elevationLoss());
            entity.setAvgElevation(dto.avgElevation());
            entity.setMaxElevation(dto.maxElevation());
            entity.setMinElevation(dto.minElevation());
            entity.setAverageSpeed(dto.averageSpeed());
            entity.setMaxSpeed(dto.maxSpeed());
            entity.setAverageHr(toInt(dto.averageHr()));
            entity.setMaxHr(toInt(dto.maxHr()));
            entity.setCalories(dto.calories());
            entity.setBmrCalories(dto.bmrCalories());
            entity.setAvgPower(dto.avgPower());
            entity.setMaxPower(dto.maxPower());
            entity.setNormPower(dto.normPower());
            entity.setMax20minPower(dto.max20minPower());
            entity.setIntensityFactor(dto.intensityFactor());
            entity.setTrainingStressScore(dto.trainingStressScore());
            entity.setAvgCadence(dto.avgCadence());
            entity.setMaxCadence(dto.maxCadence());
            entity.setAvgLeftBalance(dto.avgLeftBalance());
            entity.setAerobicTrainingEffect(dto.aerobicTrainingEffect());
            entity.setAnaerobicTrainingEffect(dto.anaerobicTrainingEffect());
            entity.setTrainingEffectLabel(dto.trainingEffectLabel());
            entity.setActivityTrainingLoad(dto.activityTrainingLoad());
            entity.setPowerZone1Seconds(dto.powerZone1Seconds());
            entity.setPowerZone2Seconds(dto.powerZone2Seconds());
            entity.setPowerZone3Seconds(dto.powerZone3Seconds());
            entity.setPowerZone4Seconds(dto.powerZone4Seconds());
            entity.setPowerZone5Seconds(dto.powerZone5Seconds());
            entity.setPowerZone6Seconds(dto.powerZone6Seconds());
            entity.setPowerZone7Seconds(dto.powerZone7Seconds());
            entity.setLapCount(dto.lapCount());
            entity.setStrokes(dto.strokes());
            entity.setAvgRespirationRate(dto.avgRespirationRate());
            entity.setMinTemperature(dto.minTemperature());
            entity.setMaxTemperature(dto.maxTemperature());
            entity.setVo2maxValue(dto.vo2maxValue());
            entity.setDeviceId(dto.deviceId());
            if (existing == null) {
                activityMapper.insert(entity);
            } else {
                activityMapper.updateById(entity);
            }
            affected++;
        }
        return affected;
    }

    /**
     * Garmin 的时长字段是浮点秒，存库前取整。
     *
     * @param value 浮点秒
     * @return 整数秒，入参为空时返回 null
     */
    private Integer toInt(Double value) {
        return value == null ? null : (int) Math.round(value);
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
        // Garmin 原始格式是 "yyyy-MM-dd HH:mm:ss"，采集器会转成 ISO；
        // 两种都接受，避免任一侧格式变动导致时间列静默为空。
        String normalized = value.trim().replace(' ', 'T');
        try {
            return LocalDateTime.parse(normalized);
        } catch (DateTimeParseException exception) {
            log.warn("采集器上报了无法解析的时间: {}", value);
            return null;
        }
    }
}
