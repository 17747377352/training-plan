package com.trainingplan.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * HRV 状态记录实体。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Data
@TableName("hrv_record")
public class HrvRecord {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** Garmin 账号 ID。 */
    private Long garminAccountId;

    /** 数据归属日期。 */
    private LocalDate calendarDate;

    /** 夜间平均 HRV（毫秒）。 */
    private Double lastNightAvg;

    /** 7 天平均 HRV（毫秒）。 */
    private Double weeklyAvg;

    /** HRV 状态，如 BALANCED/UNBALANCED。 */
    private String hrvStatus;

    /** 基线区间上界（低区间）。 */
    private Double baselineLowUpper;

    /** 平衡区间下界。 */
    private Double baselineBalancedLow;

    /** 平衡区间上界。 */
    private Double baselineBalancedUpper;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;
}
