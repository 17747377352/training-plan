package com.trainingplan.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 阈值心率（Garmin 的乳酸阈值心率）历史实体。
 *
 * <p>Garmin 只提供跑步系列，骑行系列实测为空；数值是稀疏的变更历史，不是逐日数据。</p>
 *
 * @author gongxuesong
 * @date 2026-09-24
 */
@Data
@TableName("threshold_hr")
public class ThresholdHr {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** Garmin 账号 ID。 */
    private Long garminAccountId;

    /** 该阈值心率的生效日期。 */
    private LocalDate effectiveDate;

    /** 系列：RUNNING / CYCLING。 */
    private String series;

    /** 阈值心率（bpm）。 */
    private Integer heartRate;

    /** 来源：GARMIN 接口测得 / MANUAL 手工录入。 */
    private String source;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;
}
