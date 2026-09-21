package com.trainingplan.platform.service;

import com.trainingplan.platform.dto.health.DailyHealthTrendDto;
import com.trainingplan.platform.dto.health.HrvTrendDto;
import com.trainingplan.platform.dto.health.SleepTrendDto;
import com.trainingplan.platform.dto.health.TrendQuery;

import java.util.List;

/** 当前用户的健康、HRV 与睡眠趋势查询服务。 */
public interface HealthTrendService {

    List<DailyHealthTrendDto> listDailyHealth(Long userId, TrendQuery query);

    List<HrvTrendDto> listHrv(Long userId, TrendQuery query);

    List<SleepTrendDto> listSleep(Long userId, TrendQuery query);
}
