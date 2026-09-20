package com.trainingplan.platform.service;

import com.trainingplan.platform.dto.garmin.ConnectGarminMfaRequest;
import com.trainingplan.platform.dto.garmin.ConnectGarminRequest;
import com.trainingplan.platform.dto.garmin.GarminAccountDto;
import com.trainingplan.platform.dto.garmin.GarminConnectResultDto;

import java.util.List;

/**
 * Garmin 账号绑定与维护服务。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
public interface GarminAccountService {

    /**
     * 查询当前用户绑定的 Garmin 账号。
     *
     * @param userId 平台用户 ID
     * @return 账号列表，不含任何 Token 字段
     */
    List<GarminAccountDto> listAccounts(Long userId);

    /**
     * 连接 Garmin 账号，可能直接完成或进入 MFA 流程。
     *
     * @param userId  平台用户 ID
     * @param request 连接请求
     * @return 连接结果
     */
    GarminConnectResultDto connect(Long userId, ConnectGarminRequest request);

    /**
     * 提交 MFA 验证码完成连接。
     *
     * @param userId  平台用户 ID
     * @param request MFA 请求
     * @return 连接结果
     */
    GarminConnectResultDto submitMfa(Long userId, ConnectGarminMfaRequest request);

    /**
     * 用已存令牌验证账号是否仍然可用。
     *
     * <p>令牌失效时会把账号标记为 {@code REAUTH_REQUIRED} 并抛出需要重新认证的错误。</p>
     *
     * @param userId    平台用户 ID
     * @param accountId Garmin 账号 ID
     * @return 校验后的账号信息
     */
    GarminAccountDto verifyAccount(Long userId, Long accountId);

    /**
     * 启用或暂停自动同步。
     *
     * @param userId      平台用户 ID
     * @param accountId   Garmin 账号 ID
     * @param syncEnabled 0 暂停，1 启用
     */
    void updateAutoSync(Long userId, Long accountId, Integer syncEnabled);

    /**
     * 删除账号及其本地绑定信息。
     *
     * @param userId    平台用户 ID
     * @param accountId Garmin 账号 ID
     */
    void deleteAccount(Long userId, Long accountId);
}
