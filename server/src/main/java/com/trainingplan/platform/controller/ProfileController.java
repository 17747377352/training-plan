package com.trainingplan.platform.controller;

import com.trainingplan.platform.common.api.Result;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.profile.ProfileOverviewDto;
import com.trainingplan.platform.service.ProfileOverviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人中心接口：当前登录用户的 Garmin 身体数据。
 *
 * @author gongxuesong
 * @date 2026-09-24
 */
@RestController
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileOverviewService profileOverviewService;

    /**
     * 身体数据聚合视图：FTP、VO2max、压力、身体电量、阈值心率、身体年龄与最近一次睡眠。
     *
     * @param jwt 当前登录令牌
     * @return 各项最新值，账号下没有数据的项为 null
     */
    @GetMapping("/api/profile/overview")
    public Result<ProfileOverviewDto> overview(@AuthenticationPrincipal Jwt jwt) {
        return Result.success(profileOverviewService.overview(currentUserId(jwt)));
    }

    private Long currentUserId(Jwt jwt) {
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
