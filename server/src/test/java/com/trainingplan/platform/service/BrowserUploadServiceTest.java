package com.trainingplan.platform.service;

import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.garmin.BrowserUploadPairRequest;
import com.trainingplan.platform.dto.garmin.BrowserUploadPairResult;
import com.trainingplan.platform.dto.garmin.BrowserUploadRequest;
import com.trainingplan.platform.dto.sync.DailyHealthDto;
import com.trainingplan.platform.dto.sync.SyncIngestRequest;
import com.trainingplan.platform.entity.GarminAccount;
import com.trainingplan.platform.entity.SyncJob;
import com.trainingplan.platform.mapper.GarminAccountMapper;
import com.trainingplan.platform.mapper.SyncJobMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.trainingplan.platform.mapper.BrowserUploadCredentialMapper;
import com.trainingplan.platform.service.impl.BrowserUploadServiceImpl;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 校验凭据隔离、批次范围以及事务入库的服务调用边界。 */
@ExtendWith(MockitoExtension.class)
class BrowserUploadServiceTest {

    @Mock GarminPairCodeService pairCodes;
    @Mock GarminAccountMapper accounts;
    @Mock SyncJobMapper jobs;
    @Mock BrowserUploadCredentialMapper credentials;
    @Mock SyncService sync;
    @Mock UserService users;
    @Mock com.trainingplan.platform.mapper.TrainingStatusMapper trainingStatusMapper;
    BrowserUploadService service;

    @BeforeEach
    void setUp() {
        service = new BrowserUploadServiceImpl(pairCodes, accounts, jobs, credentials, sync, users,
                trainingStatusMapper);
    }

    @Test
    void pairRotatesTokenAndClearsOldDiCredential() {
        GarminAccount account = account();
        when(pairCodes.consume("PAIRCODE")).thenReturn(7L);
        when(accounts.selectOne(any())).thenReturn(account);

        BrowserUploadPairResult result = service.pair(
                new BrowserUploadPairRequest("PAIRCODE", "Rider@Example.com", "GLOBAL"));

        assertEquals(11L, result.accountId());
        assertTrue(result.uploadToken().length() >= 32);
        verify(credentials).activateBrowserAccount(11L);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(credentials).upsertCredential(eq(11L), hash.capture());
        assertEquals(64, hash.getValue().length());
        assertTrue(!hash.getValue().equals(result.uploadToken()));
    }

    @Test
    void pairWarnsWhenLoadFocusIsMissing() {
        // 浏览器上传拿不到负荷分布；配对会把账号切成浏览器来源并清掉服务器令牌，
        // 所以顺序反了就得重新导入令牌。这里不阻止配对，但必须把话说清楚。
        when(pairCodes.consume("PAIRCODE")).thenReturn(7L);
        when(accounts.selectOne(any())).thenReturn(account());
        when(trainingStatusMapper.selectCount(any())).thenReturn(0L);

        BrowserUploadPairResult result = service.pair(
                new BrowserUploadPairRequest("PAIRCODE", "rider@example.com", "GLOBAL"));

        assertNotNull(result.warning());
        assertTrue(result.warning().contains("负荷分布"));
    }

    @Test
    void pairDoesNotWarnWhenLoadFocusAlreadyCollected() {
        when(pairCodes.consume("PAIRCODE")).thenReturn(7L);
        when(accounts.selectOne(any())).thenReturn(account());
        when(trainingStatusMapper.selectCount(any())).thenReturn(201L);

        BrowserUploadPairResult result = service.pair(
                new BrowserUploadPairRequest("PAIRCODE", "rider@example.com", "GLOBAL"));

        assertNull(result.warning());
    }

    @Test
    void unknownTokenCannotInsertJobOrData() {
        when(credentials.selectAccountIdByTokenHashForUpdate(anyString())).thenReturn(null);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.ingest("a".repeat(40), request("2026-09-23")));
        assertEquals(ErrorCode.UNAUTHORIZED, error.getErrorCode());
        verify(jobs, never()).insert(any(SyncJob.class));
        verify(sync, never()).ingest(any(), any());
    }

    @Test
    void outOfRangeDataCannotBeIngested() {
        when(credentials.selectAccountIdByTokenHashForUpdate(anyString())).thenReturn(11L);
        when(accounts.selectById(11L)).thenReturn(account());
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.ingest("a".repeat(40), request("2026-09-21")));
        assertEquals(ErrorCode.PARAM_ERROR, error.getErrorCode());
        verify(jobs, never()).insert(any(SyncJob.class));
    }

    @Test
    void validBatchUsesExistingIngestAndCompletesJob() {
        when(credentials.selectAccountIdByTokenHashForUpdate(anyString())).thenReturn(11L);
        when(accounts.selectById(11L)).thenReturn(account());
        when(jobs.insert(any(SyncJob.class))).thenAnswer(call -> {
            ((SyncJob) call.getArgument(0)).setId(42L);
            return 1;
        });
        Long jobId = service.ingest("a".repeat(40), request("2026-09-23"));
        assertEquals(42L, jobId);
        verify(sync).ingest(eq(42L), any());
        verify(sync).complete(42L);
    }

    @Test
    void cannotRevokeAnotherUsersCredential() {
        when(accounts.selectById(11L)).thenReturn(account());
        BusinessException error = assertThrows(BusinessException.class, () -> service.revoke(8L, 11L));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
        verify(credentials, never()).deleteByAccountId(any());
    }

    @Test
    void ownerCanRevokeCredential() {
        when(accounts.selectById(11L)).thenReturn(account());
        service.revoke(7L, 11L);
        verify(credentials).deleteByAccountId(11L);
    }

    @Test
    void pausedAccountCannotUpload() {
        GarminAccount account = account();
        account.setSyncEnabled(0);
        when(credentials.selectAccountIdByTokenHashForUpdate(anyString())).thenReturn(11L);
        when(accounts.selectById(11L)).thenReturn(account);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.ingest("a".repeat(40), request("2026-09-23")));
        assertEquals(ErrorCode.FORBIDDEN, error.getErrorCode());
        verify(sync, never()).ingest(any(), any());
    }

    @Test
    void nullRowIsParameterErrorInsteadOfPartialSuccess() {
        when(credentials.selectAccountIdByTokenHashForUpdate(anyString())).thenReturn(11L);
        when(accounts.selectById(11L)).thenReturn(account());
        SyncIngestRequest data = new SyncIngestRequest(java.util.Arrays.asList((DailyHealthDto) null),
                null, null, null, null, null, null, null);
        BrowserUploadRequest request = new BrowserUploadRequest(LocalDate.of(2026, 9, 23),
                LocalDate.of(2026, 9, 23), data);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.ingest("a".repeat(40), request));
        assertEquals(ErrorCode.PARAM_ERROR, error.getErrorCode());
        verify(jobs, never()).insert(any(SyncJob.class));
    }

    private static GarminAccount account() {
        GarminAccount account = new GarminAccount();
        account.setId(11L);
        account.setUserId(7L);
        account.setAuthStatus("ACTIVE");
        account.setSyncEnabled(1);
        return account;
    }

    private static BrowserUploadRequest request(String date) {
        DailyHealthDto daily = new DailyHealthDto(date, 8000, null, null, null,
                null, null, null, null, null, null);
        SyncIngestRequest data = new SyncIngestRequest(List.of(daily), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
        return new BrowserUploadRequest(LocalDate.of(2026, 9, 23), LocalDate.of(2026, 9, 23), data);
    }
}
