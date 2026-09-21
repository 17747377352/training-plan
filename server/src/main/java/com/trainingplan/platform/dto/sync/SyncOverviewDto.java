package com.trainingplan.platform.dto.sync;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 同步任务看板顶部的汇总信息。
 *
 * <p>「上次成功同步时间」取自任务表里最后一条成功任务的结束时间，
 * 而不是账号上的 {@code last_sync_time}：后者只反映最近一次成功的写入，
 * 任务表能同时回答「最近一次成功」和「最近一次失败」。</p>
 *
 * @param lastSuccessTime  最近一次成功同步的结束时间
 * @param lastFailureTime  最近一次失败任务的结束时间
 * @param unfinishedCount  进行中（PENDING + RUNNING）任务数
 * @param failureCount7d   最近 7 天失败任务数
 * @param hasGarminAccount 当前用户是否已绑定 Garmin 账号
 * @param accounts         按账号维度的最近同步情况
 * @author gongxuesong
 * @date 2026-09-21
 */
public record SyncOverviewDto(LocalDateTime lastSuccessTime,
                              LocalDateTime lastFailureTime,
                              long unfinishedCount,
                              long failureCount7d,
                              boolean hasGarminAccount,
                              List<AccountSyncStateDto> accounts) {
}
