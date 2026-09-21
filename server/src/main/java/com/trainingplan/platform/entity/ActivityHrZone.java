package com.trainingplan.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 活动心率区间分布实体。
 *
 * <p>区间个数随账号心率设置变化，因此单独建表而不是在 {@code activity} 上固定列。</p>
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
@Data
@TableName("activity_hr_zone")
public class ActivityHrZone {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 活动主键。 */
    private Long activityId;

    /** 区间序号，从 1 开始。 */
    private Integer zoneNumber;

    /** 该区间心率下界（bpm）。 */
    private Integer zoneLowBoundary;

    /** 该区间停留秒数。 */
    private Integer secondsInZone;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;
}
