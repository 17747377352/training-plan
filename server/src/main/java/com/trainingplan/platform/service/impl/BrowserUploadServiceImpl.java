package com.trainingplan.platform.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.garmin.BrowserUploadPairRequest;
import com.trainingplan.platform.dto.garmin.BrowserUploadPairResult;
import com.trainingplan.platform.dto.garmin.BrowserUploadRequest;
import com.trainingplan.platform.dto.sync.SyncIngestRequest;
import com.trainingplan.platform.entity.GarminAccount;
import com.trainingplan.platform.entity.SyncJob;
import com.trainingplan.platform.mapper.GarminAccountMapper;
import com.trainingplan.platform.mapper.SyncJobMapper;
import lombok.RequiredArgsConstructor;
import com.trainingplan.platform.mapper.BrowserUploadCredentialMapper;
import com.trainingplan.platform.service.BrowserUploadService;
import com.trainingplan.platform.service.GarminPairCodeService;
import com.trainingplan.platform.service.SyncService;
import com.trainingplan.platform.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import com.trainingplan.platform.entity.TrainingStatus;
import com.trainingplan.platform.mapper.TrainingStatusMapper;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * 浏览器采集数据的配对与入库实现。
 *
 * <p>Garmin 密码、Cookie 与浏览器会话留在本机，平台只接受白名单业务 DTO。
 * 上传凭据仅能向配对账号写入数据，不可用于平台登录或操作其他账号。</p>
 *
 * @author gongxuesong
 * @date 2026-09-24
 */
@Service
@RequiredArgsConstructor
public class BrowserUploadServiceImpl implements BrowserUploadService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final GarminPairCodeService pairCodes;
    private final GarminAccountMapper accounts;
    private final SyncJobMapper jobs;
    private final BrowserUploadCredentialMapper credentials;
    private final SyncService sync;
    private final UserService users;
    private final TrainingStatusMapper trainingStatusMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BrowserUploadPairResult pair(BrowserUploadPairRequest request) {
        Long userId = pairCodes.consume(request.code());
        users.getProfile(userId);
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String emailHash = sha256(email);
        GarminAccount account = accounts.selectOne(Wrappers.<GarminAccount>lambdaQuery()
                .eq(GarminAccount::getUserId, userId)
                .eq(GarminAccount::getRegion, request.region())
                .eq(GarminAccount::getGarminEmailHash, emailHash));
        if (account == null) {
            account = new GarminAccount();
            account.setUserId(userId);
            account.setRegion(request.region());
            account.setGarminEmailHash(emailHash);
            int at = email.indexOf('@');
            account.setGarminEmailMasked(email.charAt(0) + "***" + email.substring(at));
            account.setAuthStatus("ACTIVE");
            account.setSyncEnabled(1);
            accounts.insert(account);
        } else {
            // 浏览器模式不再向服务器交付 Garmin DI 令牌，也不能被旧 Collector 定时任务拉取。
            credentials.activateBrowserAccount(account.getId());
        }
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        credentials.upsertCredential(account.getId(), sha256(token));
        return new BrowserUploadPairResult(account.getId(), token, loadFocusWarning(account.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long ingest(String token, BrowserUploadRequest request) {
        if (token == null || token.length() < 32 || token.length() > 128) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        Long accountId = credentials.selectAccountIdByTokenHashForUpdate(sha256(token));
        if (accountId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        GarminAccount account = accounts.selectById(accountId);
        if (account == null || !"ACTIVE".equals(account.getAuthStatus())
                || !Integer.valueOf(1).equals(account.getSyncEnabled())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        users.getProfile(account.getUserId());
        validate(request);
        SyncJob job = new SyncJob();
        job.setGarminAccountId(accountId);
        job.setJobType("BROWSER");
        job.setJobStatus("RUNNING");
        job.setStartDate(request.startDate());
        job.setEndDate(request.endDate());
        job.setRequestedBy(account.getUserId());
        job.setStartedTime(LocalDateTime.now());
        jobs.insert(job);
        sync.ingest(job.getId(), request.data());
        sync.complete(job.getId());
        return job.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long userId, Long accountId) {
        GarminAccount account = accounts.selectById(accountId);
        if (account == null || !userId.equals(account.getUserId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        credentials.deleteByAccountId(accountId);
    }

    /** 入库前校验范围和去重键，防止底层跳过坏记录后仍向助手报告成功。 */
    private void validate(BrowserUploadRequest request) {
        if (request == null || request.startDate() == null || request.endDate() == null
                || request.data() == null || request.startDate().isAfter(request.endDate())
                || request.endDate().isAfter(LocalDate.now().plusDays(1))
                || ChronoUnit.DAYS.between(request.startDate(), request.endDate()) > 30) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        SyncIngestRequest data = request.data();
        List<String> dates = new ArrayList<>();
        checkedRows(data.dailyHealth()).forEach(row -> dates.add(row.calendarDate()));
        checkedRows(data.sleep()).forEach(row -> {
            dates.add(row.calendarDate());
            validateTime(row.sleepStartGmt());
            if (row.sleepEndGmt() != null) validateTime(row.sleepEndGmt());
        });
        checkedRows(data.naps()).forEach(row -> {
            dates.add(row.calendarDate());
            validateTime(row.napStartGmt());
            if (row.napEndGmt() != null) validateTime(row.napEndGmt());
        });
        checkedRows(data.hrv()).forEach(row -> dates.add(row.calendarDate()));
        checkedRows(data.trainingStatus()).forEach(row -> dates.add(row.calendarDate()));
        checkedRows(data.ftpHistory()).forEach(row -> {
            dates.add(row.effectiveDate());
            if (row.ftpWatts() == null || row.ftpWatts() <= 0) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "FTP 必须为正数");
            }
        });
        java.util.Set<Long> activityIds = new java.util.HashSet<>();
        checkedRows(data.activities()).forEach(row -> {
            if (row.garminActivityId() == null || row.garminActivityId() <= 0) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "活动缺少有效 ID");
            }
            activityIds.add(row.garminActivityId());
            validateTime(row.startTimeGmt());
            // 日期范围按设备本地日期检查，入库时间仍分别保留 GMT 与本地时间。
            String localTime = row.startTimeLocal() == null ? row.startTimeGmt() : row.startTimeLocal();
            validateTime(localTime);
            dates.add(localTime.trim().substring(0, 10));
        });
        if (dates.isEmpty() || dates.size() > 1000) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "上传数据为空或超过批次上限");
        }
        // 区间必须随对应活动一起上传，避免底层因找不到活动而静默忽略。
        if (data.activityHrZones() != null) {
            if (data.activityHrZones().size() > 500) throw new BusinessException(ErrorCode.PARAM_ERROR);
            data.activityHrZones().forEach((activityId, zones) -> {
                if (!activityIds.contains(activityId) || zones == null || zones.size() > 10) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "活动心率区间缺少对应活动");
                }
                checkedRows(zones).forEach(zone -> {
                    if (zone.zoneNumber() == null || zone.zoneNumber() < 1 || zone.zoneNumber() > 10) {
                        throw new BusinessException(ErrorCode.PARAM_ERROR, "心率区间编号无效");
                    }
                });
            });
        }
        for (String value : dates) {
            try {
                LocalDate date = LocalDate.parse(value);
                if (date.isBefore(request.startDate()) || date.isAfter(request.endDate())) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR);
                }
            } catch (java.time.format.DateTimeParseException | NullPointerException exception) {
                throw new BusinessException(ErrorCode.PARAM_ERROR);
            }
        }
    }

    /** 空类型视为无数据；过大列表或 null 行直接拒绝，避免入库中途出现系统异常。 */
    private static <T> List<T> checkedRows(List<T> rows) {
        if (rows == null) return List.of();
        if (rows.size() > 500 || rows.stream().anyMatch(java.util.Objects::isNull)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        return rows;
    }

    /** 与现有入库服务使用同一 GMT 时间格式，不接受含时区偏移的本地时间。 */
    private static void validateTime(String value) {
        try {
            LocalDateTime.parse(value.trim().replace(' ', 'T'));
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
    }

    /** 对高熵上传令牌或归一化邮箱计算摘要；不记录原文。 */
    /**
     * 配对时提示负荷分布（load focus）是否有数据。
     *
     * <p>本机浏览器上传拿不到 {@code load_aerobic_*} / {@code load_anaerobic_*} 这一族
     * （上游既无端点也无 upsert），而判灯引擎的「低强度有氧不足」诊断依赖它；平台里这一族
     * 只有**令牌路径**能填。配对会把账号切成浏览器来源并清掉服务器令牌，所以顺序反了就得
     * 重新导入令牌。这里不阻止配对，只把话说清楚 —— 更好的做法是先令牌同步、再配对，
     * 因为浏览器上传不会清空已有值（upsert 走 updateById，跳过 null 字段）。</p>
     */
    private String loadFocusWarning(Long accountId) {
        Long rows = trainingStatusMapper.selectCount(Wrappers.<TrainingStatus>lambdaQuery()
                .eq(TrainingStatus::getGarminAccountId, accountId)
                .isNotNull(TrainingStatus::getLoadAerobicLow));
        if (rows != null && rows > 0) {
            return null;
        }
        return "该账号还没有负荷分布（load focus）数据：本机浏览器上传拿不到这 10 个字段，"
                + "判灯的「低强度有氧不足」诊断会缺依据。需要的话先用令牌路径同步一次再配对，"
                + "已有数据不会被浏览器上传清空。";
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
