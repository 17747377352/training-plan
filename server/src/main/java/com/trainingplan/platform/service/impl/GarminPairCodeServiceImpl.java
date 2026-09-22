package com.trainingplan.platform.service.impl;

import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.garmin.PairCodeDto;
import com.trainingplan.platform.service.GarminPairCodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;

/**
 * 桌面助手配对码：Redis 存储、单次有效、短期过期。
 *
 * <p>为什么需要它：Garmin 的登录端点按 IP 限流且配额极小（实测一次成功登录之后立刻
 * 429），服务器上多个用户共用同一个出口 IP，所以登录只能发生在用户自己的机器上。
 * 桌面助手拿到令牌后要说明「我是哪个平台用户」——让用户在助手里再登录一次平台体验太差，
 * 于是改为：用户在已登录的页面上领一个一次性配对码，助手凭码回传令牌。</p>
 *
 * <p>安全性质：8 位取自 32 字符表（约 2^40 种组合），5 分钟有效，取用即删（
 * {@code getAndDelete} 是原子操作，天然防重放），且对「不存在」与「已过期」返回同一个
 * 错误码，避免被用来判断码是否存在。配对码等价于一次性的绑定凭据，因此日志里不打印它。</p>
 *
 * @author gongxuesong
 * @date 2026-09-22
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GarminPairCodeServiceImpl implements GarminPairCodeService {

    /** 与 Garmin 无关的独立命名空间，避免和账号、同步任务键混淆。 */
    private static final String PAIR_CODE_KEY_PREFIX = "garmin:pair:";

    private static final Duration PAIR_CODE_TTL = Duration.ofMinutes(5);

    /** 32 个字符：去掉容易看错的 0/O/1/I/L。 */
    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

    private static final int CODE_LENGTH = 8;

    /** 生成时最多重试几次，避免极小概率的键冲突变成死循环。 */
    private static final int MAX_ISSUE_ATTEMPTS = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;

    @Override
    public PairCodeDto issue(Long userId) {
        for (int attempt = 0; attempt < MAX_ISSUE_ATTEMPTS; attempt++) {
            String code = randomCode();
            Boolean stored = redisTemplate.opsForValue()
                    .setIfAbsent(key(code), String.valueOf(userId), PAIR_CODE_TTL);
            if (Boolean.TRUE.equals(stored)) {
                log.info("Garmin 配对码已签发 userId={} ttlSeconds={}", userId, PAIR_CODE_TTL.toSeconds());
                return new PairCodeDto(code, PAIR_CODE_TTL.toSeconds());
            }
        }
        log.warn("Garmin 配对码签发失败 userId={} reason=key_collision", userId);
        throw new BusinessException(ErrorCode.SYSTEM_ERROR);
    }

    @Override
    public Long consume(String code) {
        String normalized = normalize(code);
        if (normalized.isEmpty()) {
            throw new BusinessException(ErrorCode.GARMIN_PAIR_CODE_INVALID);
        }
        // 取用即删：同一个码不可能被用第二次，也不需要额外的幂等判断
        String userId = redisTemplate.opsForValue().getAndDelete(key(normalized));
        if (userId == null) {
            log.warn("Garmin 配对码无效或已过期");
            throw new BusinessException(ErrorCode.GARMIN_PAIR_CODE_INVALID);
        }
        try {
            return Long.valueOf(userId);
        } catch (NumberFormatException exception) {
            // 键值只由本服务写入，走到这里说明数据被外部污染，按无效处理即可
            log.warn("Garmin 配对码载荷异常");
            throw new BusinessException(ErrorCode.GARMIN_PAIR_CODE_INVALID);
        }
    }

    /** 去掉用户可能带进来的空格与分隔符，并统一大写。 */
    private static String normalize(String code) {
        if (code == null) {
            return "";
        }
        return code.replaceAll("[^0-9A-Za-z]", "").toUpperCase(Locale.ROOT);
    }

    private static String randomCode() {
        StringBuilder builder = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            builder.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return builder.toString();
    }

    private static String key(String code) {
        return PAIR_CODE_KEY_PREFIX + code;
    }
}
