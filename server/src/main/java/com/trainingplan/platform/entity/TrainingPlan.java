package com.trainingplan.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AI 生成的每日训练计划实体。
 *
 * <p>一个用户一天只保留一份，重新生成即覆盖。{@code stepsJson} 存处方分段的
 * JSON 数组，由服务层用 ObjectMapper 序列化，不引入额外的 TypeHandler。</p>
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
@Data
@TableName("training_plan")
public class TrainingPlan {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 平台用户 ID。 */
    private Long userId;

    /** 生成时使用的 Garmin 账号，未绑定时为空。 */
    private Long garminAccountId;

    /** 计划归属日期。 */
    private LocalDate calendarDate;

    /** 生成时的恢复灯色。 */
    private String light;

    /** 计划类型：REST/RECOVERY/ENDURANCE。 */
    private String planType;

    /** 计划名称。 */
    private String title;

    /** 总时长（分钟）。 */
    private Integer durationMinutes;

    /** 强度说明。 */
    private String intensity;

    /** 依据用户实际指标的分析。 */
    private String rationale;

    /** 热身后如何按体感调整。 */
    private String adjustment;

    /** 处方分段 JSON 数组。 */
    private String stepsJson;

    /** 模型提供方。 */
    private String provider;

    /** 模型名。 */
    private String model;

    /** 提示词版本。 */
    private String promptVersion;

    /** 生成时上下文摘要，用于判断数据是否已变化。 */
    private String dataFingerprint;

    /** 生成时使用的数据量摘要。 */
    private String dataSummary;

    /** 模型生成时间。 */
    private LocalDateTime generatedAt;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;
}
