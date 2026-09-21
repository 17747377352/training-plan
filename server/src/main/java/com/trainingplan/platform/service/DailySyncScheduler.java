package com.trainingplan.platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日定时同步调度器。
 *
 * <p>默认每天上午 9:00（Asia/Shanghai）为所有启用自动同步的账号拉取当日数据。
 * 时区显式指定，避免依赖服务器本地时区。</p>
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailySyncScheduler {

    private final SyncService syncService;

    /** 每日回溯天数，默认仅当天。 */
    @Value("${app.sync.daily-days:1}")
    private Integer dailyDays;

    /**
     * 每天上午 9:00 触发一次当日同步。
     */
    @Scheduled(cron = "${app.sync.daily-cron:0 0 9 * * *}", zone = "${app.sync.zone:Asia/Shanghai}")
    public void runDailySync() {
        try {
            int created = syncService.triggerDailySyncs(dailyDays);
            log.info("每日定时同步完成，创建任务 {} 个", created);
        } catch (Exception exception) {
            // 调度异常不得中断后续调度
            log.error("每日定时同步执行失败", exception);
        }
    }
}
