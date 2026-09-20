package com.trainingplan.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日健康汇总实体。缺失指标存 NULL，不用 0 代替。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Data
@TableName("daily_health")
public class DailyHealth {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** Garmin 账号 ID。 */
    private Long garminAccountId;

    /** 数据归属日期。 */
    private LocalDate calendarDate;

    /** 步数。 */
    private Integer steps;

    /** 距离（米）。 */
    private Double distanceMeters;

    /** 总热量（千卡）。 */
    private Double totalKilocalories;

    /** 活动热量（千卡）。 */
    private Double activeKilocalories;

    /** 静息心率（bpm）。 */
    private Integer restingHeartRate;

    /** 最低心率（bpm）。 */
    private Integer minHeartRate;

    /** 最高心率（bpm）。 */
    private Integer maxHeartRate;

    /** 平均压力。 */
    private Integer averageStressLevel;

    /** 身体电量最高值。 */
    private Integer bodyBatteryHighest;

    /** 身体电量最低值。 */
    private Integer bodyBatteryLowest;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;
}
