package com.trainingplan.platform.security;

import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.config.SecurityProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 基于 AES-GCM 的 Garmin Token 加密实现。
 *
 * <p>密文格式为 {@code Base64(12 字节随机 IV || 密文 || 16 字节认证标签)}。
 * 每条记录使用独立随机 IV；账号 ID 作为附加认证数据（AAD），
 * 使密文无法被搬到另一个账号记录上解密。</p>
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Slf4j
@Component
public class AesGcmTokenCipher implements TokenCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int AES_KEY_LENGTH = 32;

    private final SecretKeySpec secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmTokenCipher(SecurityProperties properties) {
        byte[] keyBytes = decodeKey(properties.tokenCipherKey());
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    @Override
    public String encrypt(Long garminAccountId, String plaintext) {
        if (garminAccountId == null) {
            throw new IllegalArgumentException("加密 Garmin Token 时必须提供账号 ID");
        }
        byte[] iv = new byte[IV_LENGTH];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(associatedData(garminAccountId));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = ByteBuffer.allocate(iv.length + cipherText.length)
                    .put(iv)
                    .put(cipherText)
                    .array();
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Garmin Token 加密失败");
        }
    }

    @Override
    public String decrypt(Long garminAccountId, String ciphertext) {
        if (garminAccountId == null) {
            throw new IllegalArgumentException("解密 Garmin Token 时必须提供账号 ID");
        }
        byte[] combined;
        try {
            combined = Base64.getDecoder().decode(ciphertext);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.GARMIN_AUTH_REQUIRED, "Garmin Token 密文格式无效");
        }
        if (combined.length <= IV_LENGTH) {
            throw new BusinessException(ErrorCode.GARMIN_AUTH_REQUIRED, "Garmin Token 密文长度无效");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey,
                    new GCMParameterSpec(TAG_LENGTH_BITS, combined, 0, IV_LENGTH));
            cipher.updateAAD(associatedData(garminAccountId));
            byte[] plain = cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            log.warn("Garmin Token 解密失败 garminAccountId={}", garminAccountId);
            throw new BusinessException(ErrorCode.GARMIN_AUTH_REQUIRED, "Garmin Token 解密失败，需要重新认证");
        }
    }

    private byte[] associatedData(Long garminAccountId) {
        return String.valueOf(garminAccountId).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 解析并校验 Base64 密钥，长度必须为 32 字节，否则启动即失败。
     *
     * @param base64Key Base64 编码的密钥
     * @return 密钥字节
     */
    private byte[] decodeKey(String base64Key) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("app.security.token-cipher-key 必须是 Base64 编码的 32 字节密钥");
        }
        if (keyBytes.length != AES_KEY_LENGTH) {
            throw new IllegalArgumentException("app.security.token-cipher-key 解码后必须为 32 字节");
        }
        return keyBytes;
    }
}
