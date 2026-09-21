package com.trainingplan.platform.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.training.FtpDto;
import com.trainingplan.platform.dto.training.TrainingLoadTrendDto;
import com.trainingplan.platform.entity.FtpHistory;
import com.trainingplan.platform.entity.GarminAccount;
import com.trainingplan.platform.entity.TrainingStatus;
import com.trainingplan.platform.mapper.FtpHistoryMapper;
import com.trainingplan.platform.mapper.GarminAccountMapper;
import com.trainingplan.platform.mapper.TrainingStatusMapper;
import com.trainingplan.platform.service.TrainingLoadService;
import com.trainingplan.platform.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 训练负荷与 FTP 查询实现。
 *
 * <p>与健康数据一致：先由平台用户解析出 Garmin 账号，再限定数据范围，
 * 请求参数不接收账号 ID。</p>
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
@Service
@RequiredArgsConstructor
public class TrainingLoadServiceImpl implements TrainingLoadService {

    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_RANGE_DAYS = 366;

    private final TrainingStatusMapper trainingStatusMapper;
    private final FtpHistoryMapper ftpHistoryMapper;
    private final GarminAccountMapper garminAccountMapper;
    private final UserService userService;

    @Override
    public List<TrainingLoadTrendDto> listTrainingLoad(Long userId,
                                                       LocalDate startDate,
                                                       LocalDate endDate) {
        userService.getProfile(userId);
        LocalDate end = endDate == null ? LocalDate.now() : endDate;
        LocalDate start = startDate == null ? end.minusDays(DEFAULT_DAYS - 1L) : startDate;
        if (start.isAfter(end)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "开始日期不能晚于结束日期");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_RANGE_DAYS) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "单次最多查询366天数据");
        }
        List<Long> accountIds = accountIds(userId);
        if (accountIds.isEmpty()) {
            return List.of();
        }
        return trainingStatusMapper.selectList(Wrappers.<TrainingStatus>lambdaQuery()
                        .in(TrainingStatus::getGarminAccountId, accountIds)
                        .between(TrainingStatus::getCalendarDate, start, end)
                        .orderByAsc(TrainingStatus::getCalendarDate)
                        .orderByAsc(TrainingStatus::getId))
                .stream()
                .map(this::toTrend)
                .toList();
    }

    @Override
    public List<FtpDto> listFtp(Long userId) {
        userService.getProfile(userId);
        List<Long> accountIds = accountIds(userId);
        if (accountIds.isEmpty()) {
            return List.of();
        }
        return ftpHistoryMapper.selectList(Wrappers.<FtpHistory>lambdaQuery()
                        .in(FtpHistory::getGarminAccountId, accountIds)
                        .orderByAsc(FtpHistory::getEffectiveDate)
                        .orderByAsc(FtpHistory::getId))
                .stream()
                .map(row -> new FtpDto(row.getEffectiveDate(), row.getFtpWatts(), row.getSource()))
                .toList();
    }

    /**
     * 取当前用户名下的 Garmin 账号 ID。
     *
     * @param userId 平台用户 ID
     * @return 账号 ID 列表
     */
    private List<Long> accountIds(Long userId) {
        return garminAccountMapper.selectList(Wrappers.<GarminAccount>lambdaQuery()
                        .select(GarminAccount::getId)
                        .eq(GarminAccount::getUserId, userId))
                .stream()
                .map(GarminAccount::getId)
                .toList();
    }

    private TrainingLoadTrendDto toTrend(TrainingStatus entity) {
        return new TrainingLoadTrendDto(
                entity.getCalendarDate(),
                entity.getTrainingStatusPhrase(),
                entity.getAcwrPercent(),
                entity.getAcwrStatus(),
                entity.getAcwrRatio(),
                entity.getAcuteLoad(),
                entity.getChronicLoad(),
                entity.getChronicLoadMin(),
                entity.getChronicLoadMax(),
                entity.getLoadAerobicLow(),
                entity.getLoadAerobicLowTargetMin(),
                entity.getLoadAerobicLowTargetMax(),
                entity.getLoadAerobicHigh(),
                entity.getLoadAerobicHighTargetMin(),
                entity.getLoadAerobicHighTargetMax(),
                entity.getLoadAnaerobic(),
                entity.getLoadAnaerobicTargetMin(),
                entity.getLoadAnaerobicTargetMax(),
                entity.getBalanceFeedbackPhrase(),
                entity.getVo2maxValue(),
                entity.getFitnessAge());
    }
}
