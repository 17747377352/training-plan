package com.trainingplan.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 睡眠记录实体。按入睡时间去重，同一天可有多条（含午睡）。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Data
@TableName("sleep_record")
public class SleepRecord {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** Garmin 账号 ID。 */
    private Long garminAccountId;

    /** 醒来日期。 */
    private LocalDate calendarDate;

    /** 入睡时间（GMT）。 */
    private LocalDateTime sleepStartGmt;

    /** 醒来时间（GMT）。 */
    private LocalDateTime sleepEndGmt;

    /** 总睡眠时长（秒）。 */
    private Integer sleepTimeSeconds;

    /** 深睡时长（秒）。 */
    private Integer deepSleepSeconds;

    /** 浅睡时长（秒）。 */
    private Integer lightSleepSeconds;

    /** REM 时长（秒）。 */
    private Integer remSleepSeconds;

    /** 清醒时长（秒）。 */
    private Integer awakeSleepSeconds;

    /** 睡眠评分。 */
    private Integer sleepScore;

    /** 睡眠期间平均 HRV（毫秒）。 */
    private Double avgSleepHrv;

    /** 睡眠期间平均血氧（%）。 */
    private Double avgSpo2;

    /** 睡眠期间平均呼吸（次/分）。 */
    private Double avgRespiration;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;
}
