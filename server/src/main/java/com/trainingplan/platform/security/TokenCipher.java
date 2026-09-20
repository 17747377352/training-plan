package com.trainingplan.platform.security;

/**
 * Garmin Token 的对称加密服务。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
public interface TokenCipher {

    /**
     * 加密 Garmin Token。
     *
     * @param garminAccountId Garmin 账号 ID，作为附加认证数据绑定密文归属
     * @param plaintext       明文 Token JSON
     * @return Base64 编码的密文
     */
    String encrypt(Long garminAccountId, String plaintext);

    /**
     * 解密 Garmin Token。
     *
     * @param garminAccountId Garmin 账号 ID，必须与加密时一致
     * @param ciphertext      Base64 编码的密文
     * @return 明文 Token JSON
     */
    String decrypt(Long garminAccountId, String ciphertext);
}
