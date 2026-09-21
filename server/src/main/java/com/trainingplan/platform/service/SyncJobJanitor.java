package com.trainingplan.platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 同步任务清理器：定期收敛僵死任务。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncJobJanitor {

    private final SyncService syncService;

    /**
     * 按固定间隔扫描并收敛僵死任务。
     */
    @Scheduled(fixedDelayString = "${app.sync.cleanup-interval:5m}",
            initialDelayString = "${app.sync.cleanup-initial-delay:1m}")
    public void cleanupStaleJobs() {
        try {
            int affected = syncService.failStaleJobs();
            if (affected > 0) {
                log.warn("已收敛僵死同步任务 {} 个", affected);
            }
        } catch (Exception exception) {
            // 定时任务异常不得影响其他调度
            log.error("清理僵死同步任务失败", exception);
        }
    }
}
