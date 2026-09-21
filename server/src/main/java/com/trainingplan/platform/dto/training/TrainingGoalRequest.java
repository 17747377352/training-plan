package com.trainingplan.platform.dto.training;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 提交或更新训练目标。
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
@Data
public class TrainingGoalRequest {

    /** 目标类型。 */
    @NotBlank(message = "请选择目标类型")
    @Pattern(regexp = "POWER|MUSCLE|ENDURANCE|GENERAL|OTHER", message = "目标类型不合法")
    private String goalType;

    /** 目标日期。 */
    private LocalDate targetDate;

    /** 每周可训练次数。 */
    @Min(value = 1, message = "每周次数不能小于1")
    @Max(value = 14, message = "每周次数不能大于14")
    private Integer weeklySessions;

    /** 每周可投入总时长（分钟）。 */
    @Min(value = 30, message = "每周时长不能小于30分钟")
    @Max(value = 3000, message = "每周时长不能大于3000分钟")
    private Integer weeklyMinutes;

    /** 手动补充的描述。 */
    @Size(max = 1000, message = "描述长度不能超过1000")
    private String description;
}
