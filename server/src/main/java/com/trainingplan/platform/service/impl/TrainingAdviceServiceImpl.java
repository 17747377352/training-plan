package com.trainingplan.platform.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.training.TrainingAdviceDto;
import com.trainingplan.platform.entity.*;
import com.trainingplan.platform.mapper.*;
import com.trainingplan.platform.service.TrainingAdviceService;
import com.trainingplan.platform.service.UserService;
import com.trainingplan.platform.service.training.TrainingAdviceEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

/** 保留账号与日期边界的查询编排，判灯规则由纯计算引擎负责。 */
@Service
@RequiredArgsConstructor
public class TrainingAdviceServiceImpl implements TrainingAdviceService {
    private final UserService userService;
    private final GarminAccountMapper accountMapper;
    private final TrainingStatusMapper trainingMapper;
    private final HrvRecordMapper hrvMapper;
    private final SleepRecordMapper sleepMapper;
    private final FtpHistoryMapper ftpMapper;
    private final DailyCheckinMapper checkinMapper;
    private final TrainingAdviceEngine engine;

    @Override
    public TrainingAdviceDto getAdvice(Long userId, LocalDate requestedDate) {
        userService.getProfile(userId);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDate date = requestedDate == null ? today : requestedDate;
        if (date.isAfter(today)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "训练建议不能使用未来日期");
        }
        LocalDate start = date.minusDays(29);
        List<DailyCheckin> checkins = checkinMapper.selectList(Wrappers.<DailyCheckin>lambdaQuery()
                .eq(DailyCheckin::getUserId, userId)
                .between(DailyCheckin::getCalendarDate, start, date)
                .orderByAsc(DailyCheckin::getCalendarDate));
        List<Long> ids = accountMapper.selectList(Wrappers.<GarminAccount>lambdaQuery()
                        .select(GarminAccount::getId).eq(GarminAccount::getUserId, userId)
                        .orderByAsc(GarminAccount::getId))
                .stream().map(GarminAccount::getId).toList();
        if (ids.isEmpty()) {
            return engine.evaluate(date, null, "尚未绑定 Garmin 账号", null,
                    List.of(), List.of(), null, checkins);
        }
        // 先在自有账号中选最新训练快照，再固定同一个账号取 HRV / 睡眠 / FTP，避免混合基线。
        TrainingStatus training = trainingMapper.selectList(Wrappers.<TrainingStatus>lambdaQuery()
                        .in(TrainingStatus::getGarminAccountId, ids)
                        .between(TrainingStatus::getCalendarDate, start, date)
                        .orderByDesc(TrainingStatus::getCalendarDate).orderByDesc(TrainingStatus::getId)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        Long accountId = training == null ? ids.stream().min(Comparator.naturalOrder()).orElseThrow()
                : training.getGarminAccountId();
        List<HrvRecord> hrv = hrvMapper.selectList(Wrappers.<HrvRecord>lambdaQuery()
                .eq(HrvRecord::getGarminAccountId, accountId)
                .between(HrvRecord::getCalendarDate, start, date).orderByAsc(HrvRecord::getCalendarDate));
        List<SleepRecord> sleep = sleepMapper.selectList(Wrappers.<SleepRecord>lambdaQuery()
                .eq(SleepRecord::getGarminAccountId, accountId)
                .between(SleepRecord::getCalendarDate, start, date).orderByAsc(SleepRecord::getCalendarDate));
        FtpHistory ftp = ftpMapper.selectList(Wrappers.<FtpHistory>lambdaQuery()
                        .eq(FtpHistory::getGarminAccountId, accountId)
                        .le(FtpHistory::getEffectiveDate, date)
                        .orderByDesc(FtpHistory::getEffectiveDate).orderByDesc(FtpHistory::getId)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        String source = ids.size() == 1 ? "使用同一 Garmin 账号的训练、HRV、睡眠与 FTP 数据"
                : "已绑定多个账号：按最近训练快照自动选择账号 #" + accountId
                    + "（无快照时选最早绑定账号），各项 Garmin 数据均来自此账号";
        return engine.evaluate(date, accountId, source, training, hrv, sleep, ftp, checkins);
    }
}
