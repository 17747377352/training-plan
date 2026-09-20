package com.trainingplan.platform.security;

import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.config.SecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Garmin Token 加解密测试。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
class AesGcmTokenCipherTest {

    private static final String VALID_KEY =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final String OTHER_KEY =
            Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));
    private static final String TOKEN_JSON = "{\"di_token\":\"abc\",\"di_refresh_token\":\"def\"}";

    private TokenCipher tokenCipher;

    @BeforeEach
    void setUp() {
        tokenCipher = new AesGcmTokenCipher(properties(VALID_KEY));
    }

    @Test
    void shouldRoundTripToken() {
        String ciphertext = tokenCipher.encrypt(1L, TOKEN_JSON);

        assertThat(ciphertext).isNotEqualTo(TOKEN_JSON);
        assertThat(tokenCipher.decrypt(1L, ciphertext)).isEqualTo(TOKEN_JSON);
    }

    @Test
    void shouldUseDistinctIvForEachEncryption() {
        String first = tokenCipher.encrypt(1L, TOKEN_JSON);
        String second = tokenCipher.encrypt(1L, TOKEN_JSON);

        assertThat(first).isNotEqualTo(second);
        assertThat(tokenCipher.decrypt(1L, first)).isEqualTo(TOKEN_JSON);
        assertThat(tokenCipher.decrypt(1L, second)).isEqualTo(TOKEN_JSON);
    }

    @Test
    void shouldRejectCiphertextMovedToAnotherAccount() {
        String ciphertext = tokenCipher.encrypt(1L, TOKEN_JSON);

        assertThatThrownBy(() -> tokenCipher.decrypt(2L, ciphertext))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.GARMIN_AUTH_REQUIRED);
    }

    @Test
    void shouldRejectTamperedCiphertext() {
        String ciphertext = tokenCipher.encrypt(1L, TOKEN_JSON);
        byte[] raw = Base64.getDecoder().decode(ciphertext);
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> tokenCipher.decrypt(1L, tampered))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.GARMIN_AUTH_REQUIRED);
    }

    @Test
    void shouldRejectCiphertextEncryptedWithAnotherKey() {
        TokenCipher otherCipher = new AesGcmTokenCipher(properties(OTHER_KEY));
        String ciphertext = otherCipher.encrypt(1L, TOKEN_JSON);

        assertThatThrownBy(() -> tokenCipher.decrypt(1L, ciphertext))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void shouldRejectInvalidBase64Key() {
        assertThatThrownBy(() -> new AesGcmTokenCipher(properties("not-base64-!!!")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectKeyWithWrongLength() {
        String shortKey = Base64.getEncoder().encodeToString("too-short".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new AesGcmTokenCipher(properties(shortKey)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");
    }

    @Test
    void shouldRejectEncryptWithoutAccountId() {
        assertThatThrownBy(() -> tokenCipher.encrypt(null, TOKEN_JSON))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private SecurityProperties properties(String tokenCipherKey) {
        return new SecurityProperties(
                "unit-test-jwt-secret-key-with-32-plus-characters",
                "training-plan-server",
                Duration.ofMinutes(15),
                Duration.ofDays(30),
                tokenCipherKey);
    }
}
