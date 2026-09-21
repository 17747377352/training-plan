package com.trainingplan.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Garmin 同步任务实体。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Data
@TableName("sync_job")
public class SyncJob {

    /** 任务主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** Garmin 账号 ID。 */
    private Long garminAccountId;

    /** 任务类型。 */
    private String jobType;

    /** 任务状态：PENDING、RUNNING、SUCCESS、FAILED。 */
    private String jobStatus;

    /** 同步数据起始日期（含）。 */
    private LocalDate startDate;

    /** 同步数据结束日期（含）。 */
    private LocalDate endDate;

    /** 发起用户 ID，定时任务为空。 */
    private Long requestedBy;

    /** 开始时间。 */
    private LocalDateTime startedTime;

    /** 结束时间。 */
    private LocalDateTime finishedTime;

    /** 脱敏错误编码。 */
    private String errorCode;

    /** 脱敏失败原因，取自错误码文案。 */
    private String errorMessage;

    /** 重试来源任务 ID，非重试任务为空。 */
    private Long retryOfJobId;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;
}
