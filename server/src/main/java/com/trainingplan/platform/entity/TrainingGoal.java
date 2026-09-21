package com.trainingplan.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户训练目标实体。
 *
 * <p>一个用户只保留一份当前目标。目标只影响「练什么」，不影响「能不能练」——
 * 判灯仍只看恢复信号。</p>
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
@Data
@TableName("training_goal")
public class TrainingGoal {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 平台用户 ID。 */
    private Long userId;

    /** 目标类型。 */
    private String goalType;

    /** 目标日期。 */
    private LocalDate targetDate;

    /** 每周可训练次数。 */
    private Integer weeklySessions;

    /** 每周可投入总时长（分钟）。 */
    private Integer weeklyMinutes;

    /** 用户手动补充的描述。 */
    private String description;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;
}
