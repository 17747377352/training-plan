package com.trainingplan.platform.dto.sync;

import com.trainingplan.platform.common.api.PageParam;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 当前用户的同步任务列表查询条件。
 *
 * <p>刻意不接收账号 ID 之外的越权入口：账号 ID 只用于在「当前用户的账号」
 * 范围内再收窄，不能用来查别人的任务。</p>
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SyncJobQuery extends PageParam {

    /** 任务状态过滤：PENDING、RUNNING、SUCCESS、FAILED。 */
    @Pattern(regexp = "PENDING|RUNNING|SUCCESS|FAILED", message = "任务状态不合法")
    private String jobStatus;

    /** 任务类型过滤：MANUAL、SCHEDULED。 */
    @Pattern(regexp = "MANUAL|SCHEDULED", message = "任务类型不合法")
    private String jobType;

    /** 可选：只查某个 Garmin 账号的任务，必须属于当前用户。 */
    private Long garminAccountId;
}
