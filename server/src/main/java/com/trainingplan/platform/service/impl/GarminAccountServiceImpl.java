package com.trainingplan.platform.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import com.trainingplan.platform.service.GarminAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Garmin 账号绑定与维护服务实现。
 *
 * <p>MFA 会话本身由 Collector 保存在进程内存中，本服务只在 Redis 里保存
 * 「会话 ID → 账号 ID」的短时映射，用于校验调用方归属。会话不可序列化，
 * 因此不能把 Collector 的登录中间态搬到 Redis。</p>
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GarminAccountServiceImpl implements GarminAccountService {

    private static final String MFA_SESSION_KEY_PREFIX = "garmin:mfa:";
    private static final Duration MFA_SESSION_TTL = Duration.ofMinutes(10);
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_PENDING_MFA = "PENDING_MFA";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final int SYNC_ENABLED = 1;

    private final GarminAccountMapper accountMapper;
    private final GarminAuthClient authClient;
    private final TokenCipher tokenCipher;
    private final StringRedisTemplate redisTemplate;

    @Override
    public List<GarminAccountDto> listAccounts(Long userId) {
        return accountMapper.selectList(Wrappers.<GarminAccount>lambdaQuery()
                        .eq(GarminAccount::getUserId, userId)
                        .orderByDesc(GarminAccount::getId))
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public GarminConnectResultDto connect(Long userId, ConnectGarminRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String region = request.region();
        GarminAccount account = findAccount(userId, region, email);
        if (account != null && STATUS_ACTIVE.equals(account.getAuthStatus())) {
            throw new BusinessException(ErrorCode.GARMIN_ACCOUNT_EXISTS);
        }
        boolean created = false;
        if (account == null) {
            account = createPendingAccount(userId, region, email);
            created = true;
        }

        try {
            CollectorAuthResult result = authClient.connect(email, request.password(), region);
            return handleAuthResult(userId, account, result);
        } catch (BusinessException exception) {
            // 认证失败时清理本次新建的占位记录，避免账号列表里留下从未绑定成功的幽灵账号。
            if (created) {
                accountMapper.deleteById(account.getId());
            }
            throw exception;
        }
    }

    @Override
    public GarminConnectResultDto submitMfa(Long userId, ConnectGarminMfaRequest request) {
        String key = MFA_SESSION_KEY_PREFIX + request.loginSessionId();
        String accountIdValue = redisTemplate.opsForValue().get(key);
        if (!StringUtils.hasText(accountIdValue)) {
            throw new BusinessException(ErrorCode.GARMIN_MFA_SESSION_EXPIRED);
        }
        GarminAccount account = accountMapper.selectById(Long.valueOf(accountIdValue));
        if (account == null) {
            redisTemplate.delete(key);
            throw new BusinessException(ErrorCode.GARMIN_MFA_SESSION_EXPIRED);
        }
        if (!account.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        CollectorAuthResult result = authClient.submitMfa(request.loginSessionId(), request.mfaCode());
        if (result.isMfaRequired()) {
            return GarminConnectResultDto.mfaRequired(request.loginSessionId());
        }
        GarminConnectResultDto connectResult = handleAuthResult(userId, account, result);
        redisTemplate.delete(key);
        return connectResult;
    }

    @Override
    public void updateAutoSync(Long userId, Long accountId, Integer syncEnabled) {
        GarminAccount account = requireOwnedAccount(userId, accountId);
        GarminAccount update = new GarminAccount();
        update.setId(account.getId());
        update.setSyncEnabled(syncEnabled);
        accountMapper.updateById(update);
        log.info("Garmin 自动同步开关已更新 garminAccountId={} syncEnabled={}", accountId, syncEnabled);
    }

    @Override
    public void deleteAccount(Long userId, Long accountId) {
        GarminAccount account = requireOwnedAccount(userId, accountId);
        accountMapper.deleteById(account.getId());
        log.info("Garmin 账号绑定已删除 garminAccountId={} userId={}", accountId, userId);
    }

    /**
     * 处理 Collector 返回的认证结果：已连接则加密保存 Token，需要 MFA 则登记会话映射。
     *
     * @param userId  平台用户 ID
     * @param account 账号记录
     * @param result  Collector 认证结果
     * @return 对外连接结果
     */
    private GarminConnectResultDto handleAuthResult(Long userId, GarminAccount account, CollectorAuthResult result) {
        if (result.isMfaRequired()) {
            String loginSessionId = result.loginSessionId();
            if (!StringUtils.hasText(loginSessionId)) {
                throw new BusinessException(ErrorCode.GARMIN_AUTH_REQUIRED);
            }
            markPendingMfa(account);
            redisTemplate.opsForValue().set(
                    MFA_SESSION_KEY_PREFIX + loginSessionId,
                    account.getId().toString(),
                    MFA_SESSION_TTL.toSeconds(),
                    TimeUnit.SECONDS);
            return GarminConnectResultDto.mfaRequired(loginSessionId);
        }
        if (!result.isConnected() || !StringUtils.hasText(result.tokenJson())) {
            throw mapFailure(result);
        }
        GarminAccount update = new GarminAccount();
        update.setId(account.getId());
        update.setTokenCiphertext(tokenCipher.encrypt(account.getId(), result.tokenJson()));
        update.setAuthStatus(STATUS_ACTIVE);
        accountMapper.updateById(update);
        log.info("Garmin 账号绑定成功 garminAccountId={} userId={}", account.getId(), userId);
        return GarminConnectResultDto.connected(toDto(accountMapper.selectById(account.getId())));
    }

    /**
     * 把 Collector 的失败状态映射为平台错误码，只透出已脱敏的提示。
     *
     * @param result Collector 认证结果
     * @return 可直接抛出的业务异常
     */
    private BusinessException mapFailure(CollectorAuthResult result) {
        String message = StringUtils.hasText(result.message()) ? result.message() : null;
        return switch (result.status() == null ? "" : result.status()) {
            case CollectorAuthResult.STATUS_INVALID_CREDENTIALS ->
                    new BusinessException(ErrorCode.GARMIN_INVALID_CREDENTIALS);
            case CollectorAuthResult.STATUS_RATE_LIMITED ->
                    new BusinessException(ErrorCode.GARMIN_RATE_LIMITED);
            case CollectorAuthResult.STATUS_MFA_INVALID ->
                    new BusinessException(ErrorCode.GARMIN_AUTH_REQUIRED,
                            message == null ? ErrorCode.GARMIN_AUTH_REQUIRED.getMessage() : message);
            default -> new BusinessException(ErrorCode.GARMIN_CONNECT_FAILED,
                    message == null ? ErrorCode.GARMIN_CONNECT_FAILED.getMessage() : message);
        };
    }

    private void markPendingMfa(GarminAccount account) {        if (STATUS_PENDING_MFA.equals(account.getAuthStatus())) {
            return;
        }
        GarminAccount update = new GarminAccount();
        update.setId(account.getId());
        update.setAuthStatus(STATUS_PENDING_MFA);
        accountMapper.updateById(update);
    }

    private GarminAccount createPendingAccount(Long userId, String region, String email) {
        GarminAccount account = new GarminAccount();
        account.setUserId(userId);
        account.setRegion(region);
        account.setGarminEmailHash(sha256Hex(email));
        account.setGarminEmailMasked(maskEmail(email));
        account.setAuthStatus(STATUS_PENDING);
        account.setSyncEnabled(SYNC_ENABLED);
        accountMapper.insert(account);
        return account;
    }

    private GarminAccount findAccount(Long userId, String region, String email) {
        return accountMapper.selectOne(Wrappers.<GarminAccount>lambdaQuery()
                .eq(GarminAccount::getUserId, userId)
                .eq(GarminAccount::getRegion, region)
                .eq(GarminAccount::getGarminEmailHash, sha256Hex(email)));
    }

    private GarminAccount requireOwnedAccount(Long userId, Long accountId) {
        GarminAccount account = accountMapper.selectById(accountId);
        if (account == null || !account.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Garmin账号不存在");
        }
        return account;
    }

    private GarminAccountDto toDto(GarminAccount account) {
        return new GarminAccountDto(
                account.getId(),
                account.getRegion(),
                account.getGarminEmailMasked(),
                account.getAuthStatus(),
                account.getSyncEnabled(),
                account.getLastSyncTime(),
                account.getCreateTime());
    }

    /**
     * 计算邮箱摘要，用于去重且避免明文邮箱入库。
     *
     * @param email 小写邮箱
     * @return SHA-256 十六进制摘要
     */
    private String sha256Hex(String email) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(email.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR);
        }
    }

    /**
     * 生成脱敏邮箱，保留首字符与域名。
     *
     * @param email 邮箱
     * @return 形如 {@code a***@example.com} 的脱敏结果
     */
    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
