package com.trainingplan.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 骑行活动实体。刻意不含 GPS 坐标、位置名与账号姓名。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Data
@TableName("activity")
public class Activity {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** Garmin 账号 ID。 */
    private Long garminAccountId;

    /** Garmin 活动 ID。 */
    private Long garminActivityId;

    /** 活动类型稳定标识，如 road_biking。 */
    private String activityTypeKey;

    /** 活动类型 ID。 */
    private Integer activityTypeId;

    /** 父类型 ID，用于归并同类活动。 */
    private Integer parentTypeId;

    /** 活动名称。 */
    private String activityName;

    /** 开始时间（GMT）。 */
    private LocalDateTime startTimeGmt;

    /** 开始时间（本地）。 */
    private LocalDateTime startTimeLocal;

    /** 计时时长（秒）。 */
    private Integer durationSeconds;

    /** 移动时长（秒）。 */
    private Integer movingDurationSeconds;

    /** 总耗时（秒）。 */
    private Integer elapsedDurationSeconds;

    /** 距离（米）。 */
    private Double distanceMeters;

    /** 累计爬升（米）。 */
    private Double elevationGain;

    /** 累计下降（米）。 */
    private Double elevationLoss;

    /** 平均海拔（米）。 */
    private Double avgElevation;

    /** 最高海拔（米）。 */
    private Double maxElevation;

    /** 最低海拔（米）。 */
    private Double minElevation;

    /** 平均速度（米/秒）。 */
    private Double averageSpeed;

    /** 最大速度（米/秒）。 */
    private Double maxSpeed;

    /** 平均心率（bpm）。 */
    private Integer averageHr;

    /** 最大心率（bpm）。 */
    private Integer maxHr;

    /** 活动热量（千卡）。 */
    private Double calories;

    /** 基础代谢热量（千卡）。 */
    private Double bmrCalories;

    /** 平均功率（瓦）。 */
    private Double avgPower;

    /** 最大功率（瓦）。 */
    private Double maxPower;

    /** 标准化功率 NP（瓦）。 */
    private Double normPower;

    /** 20 分钟最大平均功率（瓦）。 */
    @TableField("max_20min_power")
    private Double max20minPower;

    /** 强度因子 IF。 */
    private Double intensityFactor;

    /** 训练压力分数 TSS。 */
    private Double trainingStressScore;

    /** 平均踏频（转/分）。 */
    private Double avgCadence;

    /** 最大踏频（转/分）。 */
    private Double maxCadence;

    /** 左侧发力占比（%）。 */
    private Double avgLeftBalance;

    /** 有氧训练效果。 */
    private Double aerobicTrainingEffect;

    /** 无氧训练效果。 */
    private Double anaerobicTrainingEffect;

    /** 训练效果标签。 */
    private String trainingEffectLabel;

    /** 活动训练负荷。 */
    private Double activityTrainingLoad;

    /** 功率区间 1 时长（秒）。 */
    @TableField("power_zone_1_seconds")
    private Double powerZone1Seconds;

    /** 功率区间 2 时长（秒）。 */
    @TableField("power_zone_2_seconds")
    private Double powerZone2Seconds;

    /** 功率区间 3 时长（秒）。 */
    @TableField("power_zone_3_seconds")
    private Double powerZone3Seconds;

    /** 功率区间 4 时长（秒）。 */
    @TableField("power_zone_4_seconds")
    private Double powerZone4Seconds;

    /** 功率区间 5 时长（秒）。 */
    @TableField("power_zone_5_seconds")
    private Double powerZone5Seconds;

    /** 功率区间 6 时长（秒）。 */
    @TableField("power_zone_6_seconds")
    private Double powerZone6Seconds;

    /** 功率区间 7 时长（秒）。 */
    @TableField("power_zone_7_seconds")
    private Double powerZone7Seconds;

    /** 圈数。 */
    private Integer lapCount;

    /** 总踩踏圈数。 */
    private Double strokes;

    /** 平均呼吸频率（次/分）。 */
    private Double avgRespirationRate;

    /** 最低温度（摄氏度）。 */
    private Double minTemperature;

    /** 最高温度（摄氏度）。 */
    private Double maxTemperature;

    /** 活动时的 VO2max 估算。 */
    private Double vo2maxValue;

    /** 记录设备 ID。 */
    private Long deviceId;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;
}
