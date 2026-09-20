package com.trainingplan.platform.controller;

import com.trainingplan.platform.common.api.Result;
import com.trainingplan.platform.dto.sync.SyncIngestRequest;
import com.trainingplan.platform.service.SyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 采集器内部回调接口。
 *
 * <p>不对平台用户开放，由 {@code CollectorTokenFilter} 校验内部服务凭据。
 * 令牌只在此处按任务 ID 换取，不随任务载荷进入队列。</p>
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@RestController
@RequestMapping("/internal/collector/jobs")
@RequiredArgsConstructor
public class InternalCollectorController {

    private final SyncService syncService;

    /**
     * 按任务 ID 换取 Garmin 令牌与区域。
     *
     * @param jobId 同步任务 ID
     * @return 令牌与区域
     */
    @PostMapping("/{jobId}/session")
    public Result<Map<String, String>> session(@PathVariable Long jobId) {
        return Result.success(Map.of(
                "tokenJson", syncService.loadTokenForJob(jobId),
                "region", syncService.regionForJob(jobId)));
    }

    /**
     * 标记任务开始执行。
     *
     * @param jobId 同步任务 ID
     * @return 空响应
     */
    @PostMapping("/{jobId}/running")
    public Result<Void> running(@PathVariable Long jobId) {
        syncService.markRunning(jobId);
        return Result.success(null);
    }

    /**
     * 回传同步数据。
     *
     * @param jobId   同步任务 ID
     * @param request 上报数据
     * @return 空响应
     */
    @PostMapping("/{jobId}/ingest")
    public Result<Void> ingest(@PathVariable Long jobId, @RequestBody SyncIngestRequest request) {
        syncService.ingest(jobId, request);
        return Result.success(null);
    }

    /**
     * 标记任务成功。
     *
     * @param jobId 同步任务 ID
     * @return 空响应
     */
    @PostMapping("/{jobId}/complete")
    public Result<Void> complete(@PathVariable Long jobId) {
        syncService.complete(jobId);
        return Result.success(null);
    }

    /**
     * 标记任务失败。
     *
     * @param jobId 同步任务 ID
     * @param body  含脱敏错误编码
     * @return 空响应
     */
    @PostMapping("/{jobId}/fail")
    public Result<Void> fail(@PathVariable Long jobId, @RequestBody Map<String, String> body) {
        syncService.fail(jobId, body.getOrDefault("errorCode", "GARMIN_SYNC_ERROR"));
        return Result.success(null);
    }
}
