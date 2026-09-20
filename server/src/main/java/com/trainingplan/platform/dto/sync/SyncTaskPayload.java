package com.trainingplan.platform.dto.sync;

/**
 * 投递到 Redis 的同步任务载荷。
 *
 * <p>刻意不包含 Garmin 令牌：令牌由采集器通过内部接口按任务 ID 换取，
 * 避免长期凭据落在队列里被持久化。</p>
 *
 * @param jobId           同步任务 ID
 * @param garminAccountId Garmin 账号 ID
 * @param region          站点区域
 * @param startDate       起始日期（含）
 * @param endDate         结束日期（含）
 * @author gongxuesong
 * @date 2026-09-20
 */
public record SyncTaskPayload(Long jobId,
                              Long garminAccountId,
                              String region,
                              String startDate,
                              String endDate) {
}
