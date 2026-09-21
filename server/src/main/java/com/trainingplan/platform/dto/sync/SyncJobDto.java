package com.trainingplan.platform.dto.sync;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 同步任务看板的一行。
 *
 * @param id              任务 ID
 * @param garminAccountId Garmin 账号 ID
 * @param accountLabel    账号展示名（脱敏邮箱 + 站点）
 * @param jobType         任务类型：MANUAL 手动，SCHEDULED 定时
 * @param jobStatus       任务状态：PENDING、RUNNING、SUCCESS、FAILED
 * @param startDate       数据起始日期（含），V5 之前的任务为空
 * @param endDate         数据结束日期（含），V5 之前的任务为空
 * @param requestedBy     发起用户 ID，定时任务为空
 * @param startedTime     开始执行时间
 * @param finishedTime    结束时间
 * @param durationSeconds 执行耗时（秒），未结束时为空
 * @param errorCode       脱敏错误编码
 * @param errorMessage    脱敏失败原因
 * @param retryOfJobId    重试来源任务 ID
 * @param createTime      任务创建时间
 * @author gongxuesong
 * @date 2026-09-21
 */
public record SyncJobDto(Long id,
                         Long garminAccountId,
                         String accountLabel,
                         String jobType,
                         String jobStatus,
                         LocalDate startDate,
                         LocalDate endDate,
                         Long requestedBy,
                         LocalDateTime startedTime,
                         LocalDateTime finishedTime,
                         Long durationSeconds,
                         String errorCode,
                         String errorMessage,
                         Long retryOfJobId,
                         LocalDateTime createTime) {
}
