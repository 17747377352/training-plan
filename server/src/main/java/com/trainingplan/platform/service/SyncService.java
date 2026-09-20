package com.trainingplan.platform.service;

import com.trainingplan.platform.dto.sync.SyncIngestRequest;

/**
 * Garmin 数据同步服务。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
public interface SyncService {

    /**
     * 为指定账号创建一次手动同步任务并投递到队列。
     *
     * @param userId    发起用户 ID
     * @param accountId Garmin 账号 ID
     * @param days      回溯天数，1 表示仅当天
     * @return 同步任务 ID
     */
    Long triggerSync(Long userId, Long accountId, Integer days);

    /**
     * 按任务 ID 取回解密后的 Garmin 令牌，仅供采集器通过内部接口调用。
     *
     * @param jobId 同步任务 ID
     * @return 令牌 JSON
     */
    String loadTokenForJob(Long jobId);

    /**
     * 按任务 ID 取回站点区域。
     *
     * @param jobId 同步任务 ID
     * @return 区域：GLOBAL 或 CN
     */
    String regionForJob(Long jobId);

    /**
     * 标记任务开始执行。
     *
     * @param jobId 同步任务 ID
     */
    void markRunning(Long jobId);

    /**
     * 写入采集器上报的数据（按唯一键覆盖更新）。
     *
     * @param jobId   同步任务 ID
     * @param request 上报数据
     */
    void ingest(Long jobId, SyncIngestRequest request);

    /**
     * 标记任务成功，并刷新账号的最近同步时间。
     *
     * @param jobId 同步任务 ID
     */
    void complete(Long jobId);

    /**
     * 标记任务失败。
     *
     * @param jobId     同步任务 ID
     * @param errorCode 脱敏错误编码
     */
    void fail(Long jobId, String errorCode);
}
