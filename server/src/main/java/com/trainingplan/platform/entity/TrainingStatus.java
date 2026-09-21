package com.trainingplan.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日训练状态与负荷实体。
 *
 * <p>Garmin 服务端已按用户完整历史算好 ACWR、急性/慢性负荷与负荷平衡诊断，
 * 平台只做存储，不自行推算。</p>
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
@Data
@TableName("training_status")
public class TrainingStatus {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** Garmin 账号 ID。 */
    private Long garminAccountId;

    /** 数据归属日期。 */
    private LocalDate calendarDate;

    /** Garmin 训练状态枚举值。 */
    private Integer trainingStatus;

    /** 训练状态短语，如 PRODUCTIVE_6。 */
    private String trainingStatusPhrase;

    /** 急性慢性负荷比（百分比）。 */
    private Integer acwrPercent;

    /** ACWR 区间状态，如 OPTIMAL。 */
    private String acwrStatus;

    /** 急性/慢性负荷比值。 */
    private Double acwrRatio;

    /** 7 天急性负荷。 */
    private Integer acuteLoad;

    /** 28 天慢性负荷。 */
    private Integer chronicLoad;

    /** 慢性负荷合理区间下界。 */
    private Double chronicLoadMin;

    /** 慢性负荷合理区间上界。 */
    private Double chronicLoadMax;

    /** 低强度有氧负荷。 */
    private Double loadAerobicLow;

    /** 低强度有氧目标下界。 */
    private Integer loadAerobicLowTargetMin;

    /** 低强度有氧目标上界。 */
    private Integer loadAerobicLowTargetMax;

    /** 高强度有氧负荷。 */
    private Double loadAerobicHigh;

    /** 高强度有氧目标下界。 */
    private Integer loadAerobicHighTargetMin;

    /** 高强度有氧目标上界。 */
    private Integer loadAerobicHighTargetMax;

    /** 无氧负荷。 */
    private Double loadAnaerobic;

    /** 无氧目标下界。 */
    private Integer loadAnaerobicTargetMin;

    /** 无氧目标上界。 */
    private Integer loadAnaerobicTargetMax;

    /** 负荷平衡诊断，如 AEROBIC_LOW_SHORTAGE。 */
    private String balanceFeedbackPhrase;

    /** 最新 VO2max。 */
    private Double vo2maxValue;

    /** Garmin 给出的体能年龄。 */
    private Integer fitnessAge;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;
}
