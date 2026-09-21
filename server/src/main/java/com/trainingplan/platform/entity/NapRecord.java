package com.trainingplan.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 午睡记录实体。
 *
 * <p>一天可能有多次午睡，所以按次存储而不是给 {@code sleep_record} 加一列合计。
 * <strong>午睡不参与判灯</strong>——{@code TrainingAdviceEngine} 只接收
 * {@code sleep_record}，看不到本表，判灯规则保持不变。</p>
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
@Data
@TableName("nap_record")
public class NapRecord {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** Garmin 账号 ID。 */
    private Long garminAccountId;

    /** 数据归属日期。 */
    private LocalDate calendarDate;

    /** 午睡开始（GMT）。 */
    private LocalDateTime napStartGmt;

    /** 午睡结束（GMT）。 */
    private LocalDateTime napEndGmt;

    /** 该次午睡时长（秒）。 */
    private Integer napSeconds;

    /** Garmin 对这次午睡的评价短语。 */
    private String napFeedback;

    /** Garmin 的午睡来源标记。 */
    private Integer napSource;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;
}
