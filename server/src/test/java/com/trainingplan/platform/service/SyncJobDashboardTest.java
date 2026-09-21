package com.trainingplan.platform.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainingplan.platform.common.api.PageResult;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.sync.SyncJobDto;
import com.trainingplan.platform.dto.sync.SyncJobQuery;
import com.trainingplan.platform.dto.sync.SyncOverviewDto;
import com.trainingplan.platform.entity.GarminAccount;
import com.trainingplan.platform.entity.SyncJob;
import com.trainingplan.platform.mapper.ActivityHrZoneMapper;
import com.trainingplan.platform.mapper.ActivityMapper;
import com.trainingplan.platform.mapper.DailyHealthMapper;
import com.trainingplan.platform.mapper.FtpHistoryMapper;
import com.trainingplan.platform.mapper.GarminAccountMapper;
import com.trainingplan.platform.mapper.HrvRecordMapper;
import com.trainingplan.platform.mapper.NapRecordMapper;
import com.trainingplan.platform.mapper.TrainingStatusMapper;
import com.trainingplan.platform.mapper.SleepRecordMapper;
import com.trainingplan.platform.mapper.SyncJobMapper;
import com.trainingplan.platform.security.TokenCipher;
import com.trainingplan.platform.service.impl.SyncServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 同步任务看板的归属校验、区间复现与失败原因。
 *
 * <p>看板是按用户隔离的数据视图，本类专门锁住三件事：只能看到自己的任务、
 * 重试必须复现原任务的区间、失败原因要落到任务表里。</p>
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
@ExtendWith(MockitoExtension.class)
class SyncJobDashboardTest {

    private static final Long USER_ID = 7L;
    private static final Long ACCOUNT_ID = 16L;
    private static final Long OTHER_ACCOUNT_ID = 99L;

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
    private NapRecordMapper napRecordMapper;
    @Mock
    private TrainingStatusMapper trainingStatusMapper;
    @Mock
    private FtpHistoryMapper ftpHistoryMapper;
    @Mock
    private ActivityHrZoneMapper activityHrZoneMapper;
    @Mock
    private TokenCipher tokenCipher;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ListOperations<String, String> listOperations;
    @Mock
    private UserService userService;

    private SyncService syncService;

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "sync-job-dashboard-test");
        TableInfoHelper.initTableInfo(assistant, SyncJob.class);
        TableInfoHelper.initTableInfo(assistant, GarminAccount.class);
        syncService = new SyncServiceImpl(syncJobMapper, accountMapper, activityMapper,
                dailyHealthMapper, sleepRecordMapper, hrvRecordMapper, napRecordMapper, trainingStatusMapper,
                ftpHistoryMapper, activityHrZoneMapper, tokenCipher, redisTemplate,
                new ObjectMapper(), userService);
        ReflectionTestUtils.setField(syncService, "taskQueue", "training-plan:sync:jobs");
        ReflectionTestUtils.setField(syncService, "pendingTimeout", java.time.Duration.ofMinutes(10));
        ReflectionTestUtils.setField(syncService, "runningTimeout", java.time.Duration.ofMinutes(60));
    }

    @Test
    void shouldScopeJobQueryToCurrentUsersAccounts() {
        when(accountMapper.selectList(any(Wrapper.class))).thenReturn(List.of(account()));
        when(syncJobMapper.selectPage(any(), any(Wrapper.class))).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<SyncJob> page =
                    invocation.getArgument(0);
            page.setTotal(1);
            page.setRecords(List.of(job(9L, ACCOUNT_ID, "SUCCESS")));
            return page;
        });

        PageResult<SyncJobDto> result = syncService.listJobs(USER_ID, new SyncJobQuery());

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.records()).singleElement()
                .satisfies(row -> assertThat(row.garminAccountId()).isEqualTo(ACCOUNT_ID));

        ArgumentCaptor<Wrapper<SyncJob>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(syncJobMapper).selectPage(any(), captor.capture());
        // 只断言参数值不够：eq(user_id) 被误写成 eq(id) 时参数值一样，必须看列名
        assertThat(captor.getValue().getTargetSql()).contains("garmin_account_id");
        assertThat(((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) captor.getValue())
                .getParamNameValuePairs().values()).contains(ACCOUNT_ID);
        verify(userService).getProfile(USER_ID);
    }

    @Test
    void shouldReturnEmptyPageWhenFilteringByAnotherUsersAccount() {
        when(accountMapper.selectList(any(Wrapper.class))).thenReturn(List.of(account()));
        SyncJobQuery query = new SyncJobQuery();
        query.setGarminAccountId(OTHER_ACCOUNT_ID);

        PageResult<SyncJobDto> result = syncService.listJobs(USER_ID, query);

        assertThat(result.records()).isEmpty();
        assertThat(result.total()).isZero();
        verify(syncJobMapper, never()).selectPage(any(), any(Wrapper.class));
    }

    @Test
    void shouldReturnEmptyOverviewWithoutGarminAccount() {
        when(accountMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        SyncOverviewDto overview = syncService.overview(USER_ID);

        assertThat(overview.hasGarminAccount()).isFalse();
        assertThat(overview.accounts()).isEmpty();
        assertThat(overview.lastSuccessTime()).isNull();
        verify(syncJobMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    void shouldSummarizeLastSuccessAndFailurePerAccount() {
        GarminAccount account = account();
        account.setLastSyncTime(LocalDateTime.of(2026, 9, 21, 9, 0, 3));
        when(accountMapper.selectList(any(Wrapper.class))).thenReturn(List.of(account));

        SyncJob latest = job(11L, ACCOUNT_ID, "FAILED");
        latest.setStartDate(LocalDate.of(2026, 9, 19));
        latest.setEndDate(LocalDate.of(2026, 9, 21));
        SyncJob success = job(10L, ACCOUNT_ID, "SUCCESS");
        success.setFinishedTime(LocalDateTime.of(2026, 9, 21, 9, 0, 3));
        SyncJob failure = job(11L, ACCOUNT_ID, "FAILED");
        failure.setFinishedTime(LocalDateTime.of(2026, 9, 21, 18, 30, 0));
        failure.setErrorMessage(ErrorCode.GARMIN_RATE_LIMITED.getMessage());

        // 依次对应：最近任务、最近成功任务、最近失败任务
        when(syncJobMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(latest), List.of(success), List.of(failure));
        when(syncJobMapper.selectCount(any(Wrapper.class))).thenReturn(1L, 2L);

        SyncOverviewDto overview = syncService.overview(USER_ID);

        assertThat(overview.hasGarminAccount()).isTrue();
        assertThat(overview.lastSuccessTime()).isEqualTo(LocalDateTime.of(2026, 9, 21, 9, 0, 3));
        assertThat(overview.lastFailureTime()).isEqualTo(LocalDateTime.of(2026, 9, 21, 18, 30, 0));
        assertThat(overview.unfinishedCount()).isEqualTo(1L);
        assertThat(overview.failureCount7d()).isEqualTo(2L);
        assertThat(overview.accounts()).singleElement().satisfies(state -> {
            assertThat(state.accountLabel()).isEqualTo("go***@example.com · 国际站");
            assertThat(state.lastErrorMessage()).isEqualTo("Garmin接口请求过于频繁");
            assertThat(state.lastStartDate()).isEqualTo(LocalDate.of(2026, 9, 19));
            assertThat(state.lastEndDate()).isEqualTo(LocalDate.of(2026, 9, 21));
        });
        // 失败原因写进任务表，看板才有「失败原因」可展示
        ArgumentCaptor<Wrapper<SyncJob>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(syncJobMapper, org.mockito.Mockito.times(3)).selectList(captor.capture());
        assertThat(captor.getAllValues().get(2).getTargetSql()).contains("job_status");
    }

    @Test
    void shouldRejectRetryForAnotherUsersJob() {
        when(accountMapper.selectList(any(Wrapper.class))).thenReturn(List.of(account()));
        when(syncJobMapper.selectById(9L)).thenReturn(job(9L, OTHER_ACCOUNT_ID, "FAILED"));
        // 别人的账号是真实存在的：若归属校验被删掉，重试就会真的建出任务来，
        // 断言 errorCode 会「恰好」仍然通过（账号查得到 → 一路走通），因此这里
        // 必须同时锁住「没有去碰这个账号」和「没有插入任务」。
        GarminAccount otherAccount = new GarminAccount();
        otherAccount.setId(OTHER_ACCOUNT_ID);
        otherAccount.setUserId(999L);
        // 正确实现根本不会查这个账号，所以用 lenient 声明「存在但不是给这个测试用的桩」
        lenient().when(accountMapper.selectById(OTHER_ACCOUNT_ID)).thenReturn(otherAccount);

        assertThatThrownBy(() -> syncService.retryJob(USER_ID, 9L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException business = (BusinessException) exception;
                    assertThat(business.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(business.getMessage()).isEqualTo("同步任务不存在");
                });

        verify(accountMapper, never()).selectById(OTHER_ACCOUNT_ID);
        verify(syncJobMapper, never()).insert(any(SyncJob.class));
    }

    @Test
    void shouldRejectRetryForUnknownJob() {
        when(accountMapper.selectList(any(Wrapper.class))).thenReturn(List.of(account()));
        when(syncJobMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> syncService.retryJob(USER_ID, 404L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void shouldReuseOriginalRangeWhenRetrying() {
        when(accountMapper.selectList(any(Wrapper.class))).thenReturn(List.of(account()));
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(boundAccount());
        SyncJob source = job(9L, ACCOUNT_ID, "FAILED");
        source.setStartDate(LocalDate.of(2026, 9, 1));
        source.setEndDate(LocalDate.of(2026, 9, 7));
        when(syncJobMapper.selectById(9L)).thenReturn(source);
        when(syncJobMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(syncJobMapper.insert(any(SyncJob.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, SyncJob.class).setId(20L);
            return 1;
        });
        when(redisTemplate.opsForList()).thenReturn(listOperations);

        Long newJobId = syncService.retryJob(USER_ID, 9L);

        assertThat(newJobId).isEqualTo(20L);
        ArgumentCaptor<SyncJob> captor = ArgumentCaptor.forClass(SyncJob.class);
        verify(syncJobMapper).insert(captor.capture());
        SyncJob created = captor.getValue();
        // 必须复现原区间；若按「今天往回推 7 天」重算，这两条会随运行日期漂移
        assertThat(created.getStartDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(created.getEndDate()).isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(created.getRetryOfJobId()).isEqualTo(9L);
        assertThat(created.getRequestedBy()).isEqualTo(USER_ID);
        assertThat(created.getJobStatus()).isEqualTo("PENDING");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(listOperations).leftPush(anyString(), payload.capture());
        assertThat(payload.getValue()).contains("2026-09-01", "2026-09-07");
    }

    @Test
    void shouldFallBackToCreateDayWhenLegacyJobHasNoRange() {
        when(accountMapper.selectList(any(Wrapper.class))).thenReturn(List.of(account()));
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(boundAccount());
        SyncJob legacy = job(3L, ACCOUNT_ID, "FAILED");
        legacy.setCreateTime(LocalDateTime.of(2026, 9, 15, 9, 0));
        when(syncJobMapper.selectById(3L)).thenReturn(legacy);
        when(syncJobMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(syncJobMapper.insert(any(SyncJob.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, SyncJob.class).setId(21L);
            return 1;
        });
        when(redisTemplate.opsForList()).thenReturn(listOperations);

        syncService.retryJob(USER_ID, 3L);

        ArgumentCaptor<SyncJob> captor = ArgumentCaptor.forClass(SyncJob.class);
        verify(syncJobMapper).insert(captor.capture());
        assertThat(captor.getValue().getStartDate()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(captor.getValue().getEndDate()).isEqualTo(LocalDate.of(2026, 9, 15));
    }

    @Test
    void shouldRejectRetryWhileAccountHasUnfinishedJob() {
        when(accountMapper.selectList(any(Wrapper.class))).thenReturn(List.of(account()));
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(boundAccount());
        when(syncJobMapper.selectById(9L)).thenReturn(job(9L, ACCOUNT_ID, "FAILED"));
        when(syncJobMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        assertThatThrownBy(() -> syncService.retryJob(USER_ID, 9L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BUSINESS_ERROR);

        verify(syncJobMapper, never()).insert(any(SyncJob.class));
    }

    @Test
    void shouldPersistReadableFailureReason() {
        when(syncJobMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        syncService.fail(9L, ErrorCode.GARMIN_RATE_LIMITED.name());

        ArgumentCaptor<Wrapper<SyncJob>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(syncJobMapper).update(isNull(), captor.capture());
        Wrapper<SyncJob> wrapper = captor.getValue();
        assertThat(wrapper.getSqlSet())
                .contains("job_status")
                .contains("error_code")
                .contains("error_message");
        assertThat(queryParams(wrapper).values())
                .contains("FAILED")
                .contains("GARMIN_RATE_LIMITED")
                .contains("Garmin接口请求过于频繁");
        // 只有进行中的任务才允许转失败
        assertThat(whereValues(wrapper)).containsExactly(9L, "PENDING", "RUNNING");
    }

    @Test
    void shouldNotStoreArbitraryLongTextAsFailureReason() {
        when(syncJobMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        String hostile = "x".repeat(4000);

        syncService.fail(9L, hostile);

        ArgumentCaptor<Wrapper<SyncJob>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(syncJobMapper).update(isNull(), captor.capture());
        String stored = queryParams(captor.getValue()).values().stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(value -> value.startsWith("同步失败（"))
                .findFirst()
                .orElseThrow();
        assertThat(stored).hasSizeLessThan(120);
    }

    /** 状态流转必须带条件：终态任务不能被迟到的上报改写。 */
    @Test
    void shouldGuardStateTransitionsWithStatusCondition() {
        when(syncJobMapper.selectById(9L)).thenReturn(job(9L, ACCOUNT_ID, "RUNNING"));
        when(syncJobMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        syncService.complete(9L);
        syncService.fail(9L, "GARMIN_RATE_LIMITED");

        ArgumentCaptor<Wrapper<SyncJob>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(syncJobMapper, org.mockito.Mockito.times(2)).update(isNull(), captor.capture());
        // 条件必须精确等于「仍在进行中」；放宽成包含终态就会让迟到的上报复活旧任务
        assertThat(captor.getAllValues()).allSatisfy(wrapper ->
                assertThat(whereValues(wrapper)).containsExactly(9L, "PENDING", "RUNNING"));
        // 完成上报被忽略时不得刷新账号的最近同步时间
        verify(accountMapper, never()).updateById(any(GarminAccount.class));
    }

    private static java.util.Map<String, Object> queryParams(Wrapper<?> wrapper) {
        return ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapper)
                .getParamNameValuePairs();
    }

    /**
     * 取出 wrapper 的 WHERE 条件（不含 SET 子句）实际用到的参数值。
     *
     * <p>直接看 {@code getTargetSql()} 会把「被设置成的值」和「筛选条件」混在一起：
     * {@code set(jobStatus, SUCCESS)} 和 {@code in(jobStatus, [PENDING, RUNNING])}
     * 的参数都在同一个 Map 里，条件被放宽也照样能通过断言。因此这里只按
     * {@code getSqlSegment()}（WHERE 片段）里出现的占位符取值。</p>
     */
    private static java.util.List<Object> whereValues(Wrapper<?> wrapper) {
        wrapper.getSqlSet();
        String segment = wrapper.getSqlSegment();
        java.util.Map<String, Object> params = queryParams(wrapper);
        java.util.List<Object> values = new java.util.ArrayList<>();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("MPGENVAL\\d+").matcher(segment);
        while (matcher.find()) {
            values.add(params.get(matcher.group()));
        }
        return values;
    }

    @Test
    void shouldDescribeStaleJobTimeoutAsReadableReason() {
        when(syncJobMapper.update(any(SyncJob.class), any(Wrapper.class))).thenReturn(1, 1);

        assertThat(syncService.failStaleJobs()).isEqualTo(2);

        ArgumentCaptor<SyncJob> captor = ArgumentCaptor.forClass(SyncJob.class);
        verify(syncJobMapper, org.mockito.Mockito.times(2))
                .update(captor.capture(), any(Wrapper.class));
        assertThat(captor.getAllValues())
                .allSatisfy(update -> {
                    assertThat(update.getErrorCode()).isEqualTo("SYNC_JOB_TIMEOUT");
                    assertThat(update.getErrorMessage()).isEqualTo("同步任务超时未完成");
                });
    }

    private GarminAccount account() {
        GarminAccount account = new GarminAccount();
        account.setId(ACCOUNT_ID);
        account.setUserId(USER_ID);
        account.setRegion("GLOBAL");
        account.setGarminEmailMasked("go***@example.com");
        account.setAuthStatus("ACTIVE");
        account.setSyncEnabled(1);
        return account;
    }

    private GarminAccount boundAccount() {
        GarminAccount account = account();
        account.setTokenCiphertext("cipher");
        return account;
    }

    private SyncJob job(Long id, Long accountId, String status) {
        SyncJob job = new SyncJob();
        job.setId(id);
        job.setGarminAccountId(accountId);
        job.setJobType("MANUAL");
        job.setJobStatus(status);
        job.setCreateTime(LocalDateTime.of(2026, 9, 21, 12, 0));
        return job;
    }
}
