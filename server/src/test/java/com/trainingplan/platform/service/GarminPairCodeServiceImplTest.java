package com.trainingplan.platform.service;

import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.garmin.PairCodeDto;
import com.trainingplan.platform.service.impl.GarminPairCodeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配对码的安全性质：短期、单次、且能唯一定位签发它的用户。
 *
 * <p>这几条都是「看起来能跑、但一旦被改坏就等价于开了个后门」的性质，所以逐条钉住：
 * 少了 TTL 就是永久后门，把 {@code getAndDelete} 换成 {@code get} 就能被重放，
 * 把用户从请求体里取就能替别人绑定账号。</p>
 *
 * @author gongxuesong
 * @date 2026-09-22
 */
class GarminPairCodeServiceImplTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private GarminPairCodeServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new GarminPairCodeServiceImpl(redisTemplate);
    }

    @Test
    void issueStoresCodeUnderShortTtl() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        PairCodeDto dto = service.issue(7L);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).setIfAbsent(key.capture(), value.capture(), ttl.capture());

        assertThat(key.getValue()).isEqualTo("garmin:pair:" + dto.code());
        assertThat(value.getValue()).as("配对码必须记住签发它的平台用户").isEqualTo("7");
        assertThat(ttl.getValue())
                .as("没有 TTL 的配对码就是永久后门")
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(dto.expiresInSeconds()).isEqualTo(300);
    }

    @Test
    void issueProducesUnguessableCodeWithoutLookAlikeChars() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        PairCodeDto dto = service.issue(1L);

        // 32 字符表去掉 0/O/1/I/L，避免用户抄错；8 位约 2^40 种组合
        assertThat(dto.code()).hasSize(8).matches("[2-9A-HJ-NP-Z]{8}");
    }

    @Test
    void issueFailsLoudlyWhenRedisKeepsRejecting() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThatThrownBy(() -> service.issue(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SYSTEM_ERROR);
    }

    @Test
    void consumeReturnsIssuingUserAndBurnsTheCode() {
        when(valueOperations.getAndDelete("garmin:pair:ABCD2345")).thenReturn("7", null);

        assertThat(service.consume("abcd-2345")).as("大小写与分隔符应被归一化").isEqualTo(7L);

        assertThatThrownBy(() -> service.consume("ABCD2345"))
                .as("配对码必须单次有效，第二次使用要失败")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GARMIN_PAIR_CODE_INVALID);
    }

    @Test
    void consumeMustDeleteAtomicallyInsteadOfReading() {
        when(valueOperations.getAndDelete(anyString())).thenReturn("7");

        service.consume("ABCD2345");

        // 这条断言是防「顺手改成 get 再 delete」的：那样两步之间可以被并发重放
        verify(valueOperations, never()).get(anyString());
    }

    @Test
    void consumeRejectsUnknownOrExpiredCodeWithDedicatedError() {
        when(valueOperations.getAndDelete(anyString())).thenReturn(null);

        assertThatThrownBy(() -> service.consume("ZZZZZZZZ"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GARMIN_PAIR_CODE_INVALID);
    }

    @Test
    void consumeRejectsBlankCodeWithoutTouchingRedis() {
        assertThatThrownBy(() -> service.consume("   "))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GARMIN_PAIR_CODE_INVALID);

        verify(valueOperations, never()).getAndDelete(anyString());
    }
}
