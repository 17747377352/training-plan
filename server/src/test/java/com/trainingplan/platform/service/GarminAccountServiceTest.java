package com.trainingplan.platform.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.trainingplan.platform.client.CollectorAuthResult;
import com.trainingplan.platform.client.GarminAuthClient;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.garmin.ConnectGarminMfaRequest;
import com.trainingplan.platform.dto.garmin.ConnectGarminRequest;
import com.trainingplan.platform.dto.garmin.GarminAccountDto;
import com.trainingplan.platform.dto.garmin.GarminConnectResultDto;
import com.trainingplan.platform.entity.GarminAccount;
import com.trainingplan.platform.mapper.GarminAccountMapper;
import com.trainingplan.platform.security.TokenCipher;
import com.trainingplan.platform.service.impl.GarminAccountServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GarminAccountServiceTest {

    private static final Long USER_ID = 1L;
    private static final String EMAIL = "rider@example.com";

    @Mock
    private GarminAccountMapper accountMapper;
    @Mock
    private GarminAuthClient authClient;
    @Mock
    private TokenCipher tokenCipher;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private GarminAccountService garminAccountService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        garminAccountService = new GarminAccountServiceImpl(
                accountMapper, authClient, tokenCipher, redisTemplate);
    }

    @Test
    void shouldStoreEncryptedTokenWhenConnectSucceeds() {
        when(accountMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(accountMapper.insert(any(GarminAccount.class))).thenAnswer(invocation -> {
            GarminAccount account = invocation.getArgument(0);
            account.setId(10L);
            return 1;
        });
        when(authClient.connect(EMAIL, "secret", "CN"))
                .thenReturn(new CollectorAuthResult("CONNECTED", "{\"di_token\":\"t\"}", null, null));
        when(tokenCipher.encrypt(eq(10L), anyString())).thenReturn("cipher-text");
        when(accountMapper.selectById(10L)).thenReturn(activeAccount(10L));

        GarminConnectResultDto result = garminAccountService.connect(
                USER_ID, new ConnectGarminRequest(EMAIL, "secret", "CN"));

        assertThat(result.status()).isEqualTo(GarminConnectResultDto.STATUS_CONNECTED);
        assertThat(result.account().id()).isEqualTo(10L);

        ArgumentCaptor<GarminAccount> captor = ArgumentCaptor.forClass(GarminAccount.class);
        verify(accountMapper).updateById(captor.capture());
        assertThat(captor.getValue().getTokenCiphertext()).isEqualTo("cipher-text");
        assertThat(captor.getValue().getAuthStatus()).isEqualTo("ACTIVE");
        assertThat(captor.getValue().getGarminEmailHash()).isNull();
    }

    @Test
    void shouldReturnMfaRequiredAndRegisterSession() {
        when(accountMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(accountMapper.insert(any(GarminAccount.class))).thenAnswer(invocation -> {
            GarminAccount account = invocation.getArgument(0);
            account.setId(11L);
            return 1;
        });
        when(authClient.connect(EMAIL, "secret", "GLOBAL"))
                .thenReturn(new CollectorAuthResult("MFA_REQUIRED", null, "session-abc", null));

        GarminConnectResultDto result = garminAccountService.connect(
                USER_ID, new ConnectGarminRequest(EMAIL, "secret", "GLOBAL"));

        assertThat(result.status()).isEqualTo(GarminConnectResultDto.STATUS_MFA_REQUIRED);
        assertThat(result.loginSessionId()).isEqualTo("session-abc");
        verify(valueOperations).set(eq("garmin:mfa:session-abc"), eq("11"), anyLong(), any());
        verify(tokenCipher, never()).encrypt(anyLong(), anyString());
    }

    @Test
    void shouldRejectBindingAnAlreadyActiveAccount() {
        when(accountMapper.selectOne(any(Wrapper.class))).thenReturn(activeAccount(12L));

        assertThatThrownBy(() -> garminAccountService.connect(
                USER_ID, new ConnectGarminRequest(EMAIL, "secret", "CN")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.GARMIN_ACCOUNT_EXISTS);

        verify(authClient, never()).connect(anyString(), anyString(), anyString());
    }

    @Test
    void shouldRejectMfaWithExpiredSession() {
        when(valueOperations.get("garmin:mfa:missing")).thenReturn(null);

        assertThatThrownBy(() -> garminAccountService.submitMfa(
                USER_ID, new ConnectGarminMfaRequest("missing", "123456")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.GARMIN_MFA_SESSION_EXPIRED);
    }

    @Test
    void shouldRejectMfaSessionOfAnotherUser() {
        when(valueOperations.get("garmin:mfa:session-abc")).thenReturn("11");
        GarminAccount other = activeAccount(11L);
        other.setUserId(99L);
        when(accountMapper.selectById(11L)).thenReturn(other);

        assertThatThrownBy(() -> garminAccountService.submitMfa(
                USER_ID, new ConnectGarminMfaRequest("session-abc", "123456")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);

        verify(authClient, never()).submitMfa(anyString(), anyString());
    }

    @Test
    void shouldKeepSessionWhenMfaCodeIsWrong() {
        when(valueOperations.get("garmin:mfa:session-abc")).thenReturn("11");
        when(accountMapper.selectById(11L)).thenReturn(activeAccount(11L));
        when(authClient.submitMfa("session-abc", "000000"))
                .thenReturn(new CollectorAuthResult("MFA_REQUIRED", null, "session-abc", null));

        GarminConnectResultDto result = garminAccountService.submitMfa(
                USER_ID, new ConnectGarminMfaRequest("session-abc", "000000"));

        assertThat(result.status()).isEqualTo(GarminConnectResultDto.STATUS_MFA_REQUIRED);
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void shouldClearSessionAfterMfaSucceeds() {
        when(valueOperations.get("garmin:mfa:session-abc")).thenReturn("11");
        when(accountMapper.selectById(11L)).thenReturn(activeAccount(11L));
        when(authClient.submitMfa("session-abc", "123456"))
                .thenReturn(new CollectorAuthResult("CONNECTED", "{\"di_token\":\"t\"}", null, null));
        when(tokenCipher.encrypt(eq(11L), anyString())).thenReturn("cipher-text");

        GarminConnectResultDto result = garminAccountService.submitMfa(
                USER_ID, new ConnectGarminMfaRequest("session-abc", "123456"));

        assertThat(result.status()).isEqualTo(GarminConnectResultDto.STATUS_CONNECTED);
        verify(redisTemplate).delete("garmin:mfa:session-abc");
    }

    @Test
    void shouldNotOperateOnAnotherUsersAccount() {
        GarminAccount other = activeAccount(13L);
        other.setUserId(99L);
        when(accountMapper.selectById(13L)).thenReturn(other);

        assertThatThrownBy(() -> garminAccountService.updateAutoSync(USER_ID, 13L, 0))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> garminAccountService.deleteAccount(USER_ID, 13L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);

        verify(accountMapper, never()).updateById(any(GarminAccount.class));
        verify(accountMapper, never()).deleteById(anyLong());
    }

    @Test
    void shouldNotExposeTokenInListResult() {
        when(accountMapper.selectList(any(Wrapper.class))).thenReturn(List.of(activeAccount(14L)));

        List<GarminAccountDto> accounts = garminAccountService.listAccounts(USER_ID);

        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0).emailMasked()).isEqualTo("r***@example.com");
        assertThat(accounts.get(0).authStatus()).isEqualTo("ACTIVE");
    }

    private GarminAccount activeAccount(Long id) {
        GarminAccount account = new GarminAccount();
        account.setId(id);
        account.setUserId(USER_ID);
        account.setRegion("CN");
        account.setGarminEmailMasked("r***@example.com");
        account.setAuthStatus("ACTIVE");
        account.setSyncEnabled(1);
        return account;
    }
}
