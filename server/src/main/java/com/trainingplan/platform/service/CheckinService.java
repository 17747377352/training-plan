package com.trainingplan.platform.service;

import com.trainingplan.platform.dto.checkin.CheckinQuery;
import com.trainingplan.platform.dto.checkin.CheckinRequest;
import com.trainingplan.platform.dto.checkin.DailyCheckinDto;

import java.time.LocalDate;
import java.util.List;

/**
 * 每日手工打卡服务。
 *
 * <p>体重与主观疲劳度在 Garmin API 里不存在，只能由用户填写。</p>
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
public interface CheckinService {

    /**
     * 查询当前用户在日期区间内的打卡记录。
     *
     * @param userId 当前登录用户 ID
     * @param query  日期区间
     * @return 按日期升序的打卡记录
     */
    List<DailyCheckinDto> listCheckins(Long userId, CheckinQuery query);

    /**
     * 提交或更新某一天的打卡。
     *
     * @param userId       当前登录用户 ID
     * @param calendarDate 归属日期
     * @param request      打卡内容
     * @return 保存后的打卡记录
     */
    DailyCheckinDto saveCheckin(Long userId, LocalDate calendarDate, CheckinRequest request);

    /**
     * 删除某一天的打卡。
     *
     * @param userId       当前登录用户 ID
     * @param calendarDate 归属日期
     */
    void deleteCheckin(Long userId, LocalDate calendarDate);
}
