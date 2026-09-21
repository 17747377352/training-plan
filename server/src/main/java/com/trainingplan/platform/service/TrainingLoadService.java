package com.trainingplan.platform.service;

import com.trainingplan.platform.dto.training.FtpDto;
import com.trainingplan.platform.dto.training.TrainingLoadTrendDto;

import java.time.LocalDate;
import java.util.List;

/**
 * 训练负荷与 FTP 查询服务。
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
public interface TrainingLoadService {

    /**
     * 查询当前用户的每日训练状态与负荷。
     *
     * @param userId    当前登录用户 ID
     * @param startDate 开始日期，为空时取最近 30 天
     * @param endDate   结束日期，为空时取今天
     * @return 按日期升序的训练负荷
     */
    List<TrainingLoadTrendDto> listTrainingLoad(Long userId, LocalDate startDate, LocalDate endDate);

    /**
     * 查询当前用户的骑行 FTP 历史。
     *
     * @param userId 当前登录用户 ID
     * @return 按生效日期升序的 FTP 记录
     */
    List<FtpDto> listFtp(Long userId);
}
