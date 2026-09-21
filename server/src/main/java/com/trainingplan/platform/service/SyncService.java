package com.trainingplan.platform.service;

import com.trainingplan.platform.common.api.PageResult;
import com.trainingplan.platform.dto.sync.SyncIngestRequest;
import com.trainingplan.platform.dto.sync.SyncJobDto;
import com.trainingplan.platform.dto.sync.SyncJobQuery;
import com.trainingplan.platform.dto.sync.SyncOverviewDto;

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
     * 定时任务发起同步：不校验归属，`requestedBy` 记为空。
     *
     * @param accountId Garmin 账号 ID
     * @param days      回溯天数
     * @return 同步任务 ID，未创建时返回 null
     */
    Long triggerScheduledSync(Long accountId, Integer days);

    /**
     * 为所有启用自动同步的账号创建当日同步任务。
     *
     * <p>已有未完成任务在飞的账号会跳过，避免同一账号并发同步。</p>
     *
     * @param days 回溯天数
     * @return 实际创建的任务数
     */
    int triggerDailySyncs(Integer days);

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

    /**
     * 收敛僵死任务：长时间停留在 PENDING 或 RUNNING 的任务置为失败。
     *
     * <p>采集器未运行、进程被杀或与平台断连时，任务不会有人推进；
     * 不清理会让任务表长期残留假"进行中"记录。</p>
     *
     * @return 本次收敛的任务数
     */
    int failStaleJobs();

    /**
     * 分页查询当前用户的同步任务。
     *
     * <p>数据范围先由平台用户解析出 Garmin 账号，再限定在账号内；
     * 请求参数只用于在自有账号范围内收窄，不能查出别人的任务。</p>
     *
     * @param userId 当前登录用户 ID
     * @param query  过滤与分页条件
     * @return 任务分页
     */
    PageResult<SyncJobDto> listJobs(Long userId, SyncJobQuery query);

    /**
     * 同步任务看板汇总：最近成功/失败时间、进行中任务数与失败原因。
     *
     * @param userId 当前登录用户 ID
     * @return 汇总信息
     */
    SyncOverviewDto overview(Long userId);

    /**
     * 按原任务的数据区间重跑一次同步。
     *
     * @param userId 当前登录用户 ID
     * @param jobId  被重试的任务 ID，必须属于当前用户
     * @return 新任务 ID
     */
    Long retryJob(Long userId, Long jobId);
}
