package com.trainingplan.platform.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.checkin.CheckinQuery;
import com.trainingplan.platform.dto.checkin.CheckinRequest;
import com.trainingplan.platform.dto.checkin.DailyCheckinDto;
import com.trainingplan.platform.entity.DailyCheckin;
import com.trainingplan.platform.mapper.DailyCheckinMapper;
import com.trainingplan.platform.service.CheckinService;
import com.trainingplan.platform.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;

/**
 * 每日手工打卡实现。
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
@Service
@RequiredArgsConstructor
public class CheckinServiceImpl implements CheckinService {

    private final DailyCheckinMapper dailyCheckinMapper;
    private final UserService userService;

    @Override
    public List<DailyCheckinDto> listCheckins(Long userId, CheckinQuery query) {
        userService.getProfile(userId);
        LocalDate start = query.getStartDate();
        LocalDate end = query.getEndDate();
        if (start != null && end != null && start.isAfter(end)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "开始日期不能晚于结束日期");
        }
        return dailyCheckinMapper.selectList(Wrappers.<DailyCheckin>lambdaQuery()
                        .eq(DailyCheckin::getUserId, userId)
                        .ge(start != null, DailyCheckin::getCalendarDate, start)
                        .le(end != null, DailyCheckin::getCalendarDate, end)
                        .orderByAsc(DailyCheckin::getCalendarDate))
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public DailyCheckinDto saveCheckin(Long userId, LocalDate calendarDate, CheckinRequest request) {
        userService.getProfile(userId);
        if (calendarDate == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "日期不能为空");
        }
        String note = StringUtils.hasText(request.getNote()) ? request.getNote().trim() : null;
        if (request.getWeightKg() == null && request.getRpe() == null && note == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "体重、RPE 与备注不能全为空");
        }

        DailyCheckin existing = dailyCheckinMapper.selectOne(Wrappers.<DailyCheckin>lambdaQuery()
                .eq(DailyCheckin::getUserId, userId)
                .eq(DailyCheckin::getCalendarDate, calendarDate));
        DailyCheckin entity = existing == null ? new DailyCheckin() : existing;
        entity.setUserId(userId);
        entity.setCalendarDate(calendarDate);
        entity.setWeightKg(request.getWeightKg());
        entity.setRpe(request.getRpe());
        entity.setNote(note);
        if (existing == null) {
            dailyCheckinMapper.insert(entity);
        } else {
            dailyCheckinMapper.updateById(entity);
        }
        return toDto(entity);
    }

    @Override
    public void deleteCheckin(Long userId, LocalDate calendarDate) {
        userService.getProfile(userId);
        dailyCheckinMapper.delete(Wrappers.<DailyCheckin>lambdaQuery()
                .eq(DailyCheckin::getUserId, userId)
                .eq(DailyCheckin::getCalendarDate, calendarDate));
    }

    private DailyCheckinDto toDto(DailyCheckin entity) {
        return new DailyCheckinDto(entity.getCalendarDate(), entity.getWeightKg(),
                entity.getRpe(), entity.getNote());
    }
}
