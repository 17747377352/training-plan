package com.trainingplan.platform.controller;

import com.trainingplan.platform.common.api.Result;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.training.FtpDto;
import com.trainingplan.platform.dto.training.TrainingLoadTrendDto;
import com.trainingplan.platform.service.TrainingLoadService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 当前登录用户的训练负荷与 FTP 查询接口。
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TrainingLoadController {

    private final TrainingLoadService trainingLoadService;

    /**
     * 查询每日训练状态与负荷（ACWR、急性/慢性负荷、负荷平衡诊断）。
     *
     * @param startDate 开始日期
     * @param endDate   结束日期
     * @param jwt       当前登录令牌
     * @return 按日期升序的训练负荷
     */
    @GetMapping("/training-load")
    public Result<List<TrainingLoadTrendDto>> listTrainingLoad(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @AuthenticationPrincipal Jwt jwt) {
        return Result.success(trainingLoadService.listTrainingLoad(currentUserId(jwt), startDate, endDate));
    }

    /**
     * 查询骑行 FTP 历史。
     *
     * @param jwt 当前登录令牌
     * @return 按生效日期升序的 FTP 记录
     */
    @GetMapping("/ftp")
    public Result<List<FtpDto>> listFtp(@AuthenticationPrincipal Jwt jwt) {
        return Result.success(trainingLoadService.listFtp(currentUserId(jwt)));
    }

    private Long currentUserId(Jwt jwt) {
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
