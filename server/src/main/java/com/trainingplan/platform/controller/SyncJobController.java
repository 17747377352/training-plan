package com.trainingplan.platform.controller;

import com.trainingplan.platform.common.api.PageResult;
import com.trainingplan.platform.common.api.Result;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.sync.SyncJobDto;
import com.trainingplan.platform.dto.sync.SyncJobQuery;
import com.trainingplan.platform.dto.sync.SyncOverviewDto;
import com.trainingplan.platform.service.SyncService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前登录用户的同步任务看板接口。
 *
 * <p>所有查询与重试都以 JWT 的 subject 作为用户身份，再由服务层解析
 * 其名下的 Garmin 账号；请求参数不接收用户 ID。</p>
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
@RestController
@RequestMapping("/api/sync/jobs")
@RequiredArgsConstructor
public class SyncJobController {

    private final SyncService syncService;

    /**
     * 分页查询当前用户的同步任务。
     *
     * @param query 过滤与分页条件
     * @param jwt   当前登录令牌
     * @return 任务分页
     */
    @GetMapping
    public Result<PageResult<SyncJobDto>> listJobs(
            @Valid SyncJobQuery query,
            @AuthenticationPrincipal Jwt jwt) {
        return Result.success(syncService.listJobs(currentUserId(jwt), query));
    }

    /**
     * 同步任务看板汇总：最近成功/失败时间、进行中任务数与失败原因。
     *
     * @param jwt 当前登录令牌
     * @return 汇总信息
     */
    @GetMapping("/overview")
    public Result<SyncOverviewDto> overview(@AuthenticationPrincipal Jwt jwt) {
        return Result.success(syncService.overview(currentUserId(jwt)));
    }

    /**
     * 按原任务的数据区间重跑一次同步。
     *
     * @param id  被重试的任务 ID
     * @param jwt 当前登录令牌
     * @return 新任务 ID
     */
    @PostMapping("/{id}/retry")
    public Result<Long> retry(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return Result.success(syncService.retryJob(currentUserId(jwt), id));
    }

    private Long currentUserId(Jwt jwt) {
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
