package com.trainingplan.platform.client;

/**
 * Spring 服务调用 Collector 认证接口的客户端。
 *
 * <p>Garmin 密码与验证码只在本次调用中透传给 Collector，不在本项目内持久化。</p>
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
public interface GarminAuthClient {

    /**
     * 使用 Garmin 凭据发起登录。
     *
     * @param email    Garmin 邮箱
     * @param password Garmin 密码
     * @param region   站点区域：GLOBAL 或 CN
     * @return 认证结果，可能是已连接或需要 MFA
     */
    CollectorAuthResult connect(String email, String password, String region);

    /**
     * 提交 MFA 验证码完成登录。
     *
     * @param loginSessionId MFA 会话 ID
     * @param mfaCode        验证码
     * @return 认证结果
     */
    CollectorAuthResult submitMfa(String loginSessionId, String mfaCode);
}
