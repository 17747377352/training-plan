package com.trainingplan.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日手工打卡实体。
 *
 * <p>体重与主观疲劳度在 Garmin API 里不存在，只能由用户填写；
 * 按平台用户存储，没有 Garmin 账号时同样可以记录。</p>
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
@Data
@TableName("daily_checkin")
public class DailyCheckin {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 平台用户 ID。 */
    private Long userId;

    /** 数据归属日期。 */
    private LocalDate calendarDate;

    /** 体重（千克）。 */
    private BigDecimal weightKg;

    /** 主观疲劳度 1-10。 */
    private Integer rpe;

    /** 备注。 */
    private String note;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;
}
