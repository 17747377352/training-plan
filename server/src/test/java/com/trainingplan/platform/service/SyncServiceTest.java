package com.trainingplan.platform.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.sync.DailyHealthDto;
import com.trainingplan.platform.dto.sync.HrvRecordDto;
import com.trainingplan.platform.dto.sync.SleepRecordDto;
import com.trainingplan.platform.dto.sync.SyncIngestRequest;
import com.trainingplan.platform.dto.sync.ActivityDto;
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
import com.trainingplan.platform.service.impl.SyncServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long ACCOUNT_ID = 16L;

    @Mock
    private SyncJobMapper syncJobMapper;
    @Mock
    private GarminAccountMapper accountMapper;
    @Mock
    private ActivityMapper activityMapper;
    @Mock
    private DailyHealthMapper dailyHealthMapper;
    @Mock
    private SleepRecordMapper sleepRecordMapper;
    @Mock
    private HrvRecordMapper hrvRecordMapper;
    @Mock
    private TokenCipher tokenCipher;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ListOperations<String, String> listOperations;

    private SyncService syncService;

    @BeforeEach
    void setUp() {
        syncService = new SyncServiceImpl(syncJobMapper, accountMapper, activityMapper,
                dailyHealthMapper, sleepRecordMapper, hrvRecordMapper, tokenCipher, redisTemplate,
                new ObjectMapper());
        ReflectionTestUtils.setField(syncService, "taskQueue", "training-plan:sync:jobs");
    }

    @Test
    void shouldCreateJobAndPushTask() {
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(boundAccount());
        when(syncJobMapper.insert(any(SyncJob.class))).thenAnswer(invocation -> {
            SyncJob job = invocation.getArgument(0);
            job.setId(99L);
            return 1;
        });
        when(redisTemplate.opsForList()).thenReturn(listOperations);

        Long jobId = syncService.triggerSync(USER_ID, ACCOUNT_ID, 3);

        assertThat(jobId).isEqualTo(99L);
        ArgumentCaptor<SyncJob> jobCaptor = ArgumentCaptor.forClass(SyncJob.class);
        verify(syncJobMapper).insert(jobCaptor.capture());
        assertThat(jobCaptor.getValue().getJobStatus()).isEqualTo("PENDING");
        assertThat(jobCaptor.getValue().getRequestedBy()).isEqualTo(USER_ID);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(listOperations).leftPush(eq("training-plan:sync:jobs"), payload.capture());
        // 载荷不得包含令牌，令牌由采集器按任务 ID 换取
        assertThat(payload.getValue()).doesNotContain("di_token", "tokenJson");
        assertThat(payload.getValue()).contains("\"jobId\":99");
    }

    @Test
    void shouldRejectSyncForAccountWithoutToken() {
        GarminAccount account = boundAccount();
        account.setTokenCiphertext(null);
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(account);

        assertThatThrownBy(() -> syncService.triggerSync(USER_ID, ACCOUNT_ID, 1))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.GARMIN_AUTH_REQUIRED);

        verify(syncJobMapper, never()).insert(any(SyncJob.class));
    }

    @Test
    void shouldRejectSyncForAnotherUsersAccount() {
        GarminAccount account = boundAccount();
        account.setUserId(999L);
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(account);

        assertThatThrownBy(() -> syncService.triggerSync(USER_ID, ACCOUNT_ID, 1))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void shouldInsertNewRowsOnIngest() {
        when(syncJobMapper.selectById(1L)).thenReturn(job());
        when(dailyHealthMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(sleepRecordMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(hrvRecordMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        syncService.ingest(1L, new SyncIngestRequest(
                List.of(new DailyHealthDto("2026-09-20", 2795, 2280.0, 1481.0, 300.0, 51, 45, 120, 20, 61, 20)),
                List.of(new SleepRecordDto("2026-09-20", "2026-09-19T17:00:00", "2026-09-20T00:00:00",
                        26460, 6720, 15000, 3000, 600, 80, 52.3, 96.0, 14.2)),
                List.of(new HrvRecordDto("2026-09-20", 70.0, 79.0, "UNBALANCED", 79.0, 80.0, 107.0)),
                List.of()));

        verify(dailyHealthMapper).insert(any(DailyHealth.class));
        verify(sleepRecordMapper).insert(any(SleepRecord.class));
        verify(hrvRecordMapper).insert(any(HrvRecord.class));
    }

    @Test
    void shouldUpdateExistingRowsOnIngest() {
        when(syncJobMapper.selectById(1L)).thenReturn(job());
        when(dailyHealthMapper.selectOne(any(Wrapper.class))).thenReturn(new DailyHealth());
        when(sleepRecordMapper.selectOne(any(Wrapper.class))).thenReturn(new SleepRecord());
        when(hrvRecordMapper.selectOne(any(Wrapper.class))).thenReturn(new HrvRecord());

        syncService.ingest(1L, new SyncIngestRequest(
                List.of(new DailyHealthDto("2026-09-20", 1, null, null, null, null, null, null, null, null, null)),
                List.of(new SleepRecordDto("2026-09-20", "2026-09-19T17:00:00", null,
                        null, null, null, null, null, null, null, null, null)),
                List.of(new HrvRecordDto("2026-09-20", 70.0, null, null, null, null, null)),
                List.of()));

        verify(dailyHealthMapper).updateById(any(DailyHealth.class));
        verify(sleepRecordMapper).updateById(any(SleepRecord.class));
        verify(hrvRecordMapper).updateById(any(HrvRecord.class));
        verify(dailyHealthMapper, never()).insert(any(DailyHealth.class));
        verify(hrvRecordMapper, never()).insert(any(HrvRecord.class));
    }

    @Test
    void shouldSkipRowsWithUnparsableDate() {
        when(syncJobMapper.selectById(1L)).thenReturn(job());

        syncService.ingest(1L, new SyncIngestRequest(
                List.of(new DailyHealthDto("not-a-date", 1, null, null, null, null, null, null, null, null, null)),
                List.of(new SleepRecordDto("2026-09-20", "bad-time", null, null, null, null, null, null, null, null, null, null)),
                List.of(),
                List.of()));

        verify(dailyHealthMapper, never()).insert(any(DailyHealth.class));
        verify(sleepRecordMapper, never()).insert(any(SleepRecord.class));
    }

    @Test
    void shouldMarkCompleteAndTouchAccount() {
        when(syncJobMapper.selectById(1L)).thenReturn(job());

        syncService.complete(1L);

        ArgumentCaptor<SyncJob> jobCaptor = ArgumentCaptor.forClass(SyncJob.class);
        verify(syncJobMapper).updateById(jobCaptor.capture());
        assertThat(jobCaptor.getValue().getJobStatus()).isEqualTo("SUCCESS");

        ArgumentCaptor<GarminAccount> accountCaptor = ArgumentCaptor.forClass(GarminAccount.class);
        verify(accountMapper).updateById(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getLastSyncTime()).isNotNull();
    }

    @Test
    void shouldDecryptTokenForJob() {
        when(syncJobMapper.selectById(1L)).thenReturn(job());
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(boundAccount());
        when(tokenCipher.decrypt(ACCOUNT_ID, "cipher")).thenReturn("token-json");

        assertThat(syncService.loadTokenForJob(1L)).isEqualTo("token-json");
        assertThat(syncService.regionForJob(1L)).isEqualTo("GLOBAL");
    }

    @Test
    void shouldRejectUnknownJob() {
        when(syncJobMapper.selectById(any())).thenReturn(null);

        assertThatThrownBy(() -> syncService.markRunning(404L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void shouldFailStalePendingAndRunningJobs() {
        ReflectionTestUtils.setField(syncService, "pendingTimeout", Duration.ofMinutes(10));
        ReflectionTestUtils.setField(syncService, "runningTimeout", Duration.ofMinutes(60));
        // 第一次收敛 PENDING，第二次收敛 RUNNING
        when(syncJobMapper.update(any(SyncJob.class), any())).thenReturn(1, 2);

        int affected = syncService.failStaleJobs();

        assertThat(affected).isEqualTo(3);
        ArgumentCaptor<SyncJob> captor = ArgumentCaptor.forClass(SyncJob.class);
        verify(syncJobMapper, times(2)).update(captor.capture(), any());
        assertThat(captor.getAllValues()).allSatisfy(job -> {
            assertThat(job.getJobStatus()).isEqualTo("FAILED");
            assertThat(job.getErrorCode()).isEqualTo("SYNC_JOB_TIMEOUT");
            assertThat(job.getFinishedTime()).isNotNull();
        });
    }

    @Test
    void shouldReportZeroWhenNothingIsStale() {
        ReflectionTestUtils.setField(syncService, "pendingTimeout", Duration.ofMinutes(10));
        ReflectionTestUtils.setField(syncService, "runningTimeout", Duration.ofMinutes(60));
        when(syncJobMapper.update(any(SyncJob.class), any())).thenReturn(0);

        assertThat(syncService.failStaleJobs()).isZero();
    }

    @Test
    void shouldCreateDailyJobsForEnabledAccounts() {
        when(accountMapper.selectList(any(Wrapper.class))).thenReturn(List.of(boundAccount()));
        when(syncJobMapper.selectCount(any())).thenReturn(0L);
        when(syncJobMapper.insert(any(SyncJob.class))).thenAnswer(invocation -> {
            ((SyncJob) invocation.getArgument(0)).setId(200L);
            return 1;
        });
        when(redisTemplate.opsForList()).thenReturn(listOperations);

        int created = syncService.triggerDailySyncs(1);

        assertThat(created).isEqualTo(1);
        ArgumentCaptor<SyncJob> captor = ArgumentCaptor.forClass(SyncJob.class);
        verify(syncJobMapper).insert(captor.capture());
        assertThat(captor.getValue().getJobType()).isEqualTo("SCHEDULED");
        // 定时任务没有发起用户
        assertThat(captor.getValue().getRequestedBy()).isNull();
    }

    @Test
    void shouldSkipAccountsWithUnfinishedJob() {
        when(accountMapper.selectList(any(Wrapper.class))).thenReturn(List.of(boundAccount()));
        when(syncJobMapper.selectCount(any())).thenReturn(1L);

        assertThat(syncService.triggerDailySyncs(1)).isZero();
        verify(syncJobMapper, never()).insert(any(SyncJob.class));
    }

    @Test
    void shouldSkipAccountsWithoutToken() {
        GarminAccount noToken = boundAccount();
        noToken.setTokenCiphertext(null);
        when(accountMapper.selectList(any(Wrapper.class))).thenReturn(List.of(noToken));

        assertThat(syncService.triggerDailySyncs(1)).isZero();
        verify(syncJobMapper, never()).insert(any(SyncJob.class));
    }

    @Test
    void shouldNotFailWholeBatchWhenOneAccountCannotSync() {
        GarminAccount broken = boundAccount();
        broken.setTokenCiphertext(null);
        GarminAccount healthy = boundAccount();
        healthy.setId(17L);
        when(accountMapper.selectList(any(Wrapper.class))).thenReturn(List.of(broken, healthy));
        when(syncJobMapper.selectCount(any())).thenReturn(0L);
        when(syncJobMapper.insert(any(SyncJob.class))).thenAnswer(invocation -> {
            ((SyncJob) invocation.getArgument(0)).setId(201L);
            return 1;
        });
        when(redisTemplate.opsForList()).thenReturn(listOperations);

        assertThat(syncService.triggerDailySyncs(1)).isEqualTo(1);
    }

    @Test
    void shouldReturnNullWhenScheduledAccountMissing() {
        when(accountMapper.selectById(any())).thenReturn(null);

        assertThat(syncService.triggerScheduledSync(404L, 1)).isNull();
        verify(syncJobMapper, never()).insert(any(SyncJob.class));
    }

    @Test
    void shouldInsertCyclingActivityWithRealFieldNames() {
        when(syncJobMapper.selectById(1L)).thenReturn(job());
        when(activityMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        syncService.ingest(1L, new SyncIngestRequest(List.of(), List.of(), List.of(),
                List.of(new ActivityDto(12345678901L, "road_biking", 10, 2, "公路骑行",
                        "2026-09-19T00:53:51", "2026-09-19T08:53:51",
                        15299.0, 15280.0, 23489.0, 103550.0, 2322.0, 1907.0, 700.0, 1384.0, 503.0,
                        6.777, 15.8, 156.0, 185.0, 2592.0, 1200.0,
                        148.0, 388.0, 186.0, 216.0, 0.87, 321.6,
                        77.0, 101.0, 56.0, 5.0, 1.2, "LACTATE_THRESHOLD", 220.0,
                        408.0, 1192.0, 1683.0, 916.0, 258.0, 70.0, 11.0,
                        21, 15930.0, 34.0, 19.0, 33.0, 59.0, 3355668899L))));

        ArgumentCaptor<Activity> captor = ArgumentCaptor.forClass(Activity.class);
        verify(activityMapper).insert(captor.capture());
        Activity saved = captor.getValue();
        assertThat(saved.getActivityTypeKey()).isEqualTo("road_biking");
        assertThat(saved.getNormPower()).isEqualTo(186.0);
        assertThat(saved.getTrainingStressScore()).isEqualTo(321.6);
        assertThat(saved.getAvgLeftBalance()).isEqualTo(56.0);
        assertThat(saved.getPowerZone4Seconds()).isEqualTo(916.0);
        // 浮点秒落库前取整
        assertThat(saved.getMovingDurationSeconds()).isEqualTo(15280);
        assertThat(saved.getAverageHr()).isEqualTo(156);
    }

    @Test
    void shouldUpdateActivityOnReSync() {
        when(syncJobMapper.selectById(1L)).thenReturn(job());
        when(activityMapper.selectOne(any(Wrapper.class))).thenReturn(new Activity());

        syncService.ingest(1L, new SyncIngestRequest(List.of(), List.of(), List.of(),
                List.of(new ActivityDto(2L, "cycling", 2, 17, "骑行", null, null,
                        null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null))));

        verify(activityMapper).updateById(any(Activity.class));
        verify(activityMapper, never()).insert(any(Activity.class));
    }

    @Test
    void shouldParseBothSpaceAndIsoSeparatedTimes() {
        when(syncJobMapper.selectById(1L)).thenReturn(job());
        when(activityMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        syncService.ingest(1L, new SyncIngestRequest(List.of(), List.of(), List.of(),
                List.of(new ActivityDto(1L, "road_biking", 10, 2, "骑行",
                        "2026-09-19 00:53:51", "2026-09-19T08:53:51",
                        null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null))));

        ArgumentCaptor<Activity> captor = ArgumentCaptor.forClass(Activity.class);
        verify(activityMapper).insert(captor.capture());
        // 空格分隔与 ISO 分隔都应解析成功
        assertThat(captor.getValue().getStartTimeGmt()).isNotNull();
        assertThat(captor.getValue().getStartTimeLocal()).isNotNull();
    }

    private GarminAccount boundAccount() {
        GarminAccount account = new GarminAccount();
        account.setId(ACCOUNT_ID);
        account.setUserId(USER_ID);
        account.setRegion("GLOBAL");
        account.setTokenCiphertext("cipher");
        return account;
    }

    private SyncJob job() {
        SyncJob job = new SyncJob();
        job.setId(1L);
        job.setGarminAccountId(ACCOUNT_ID);
        job.setJobStatus("RUNNING");
        return job;
    }
}
