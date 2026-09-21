package com.trainingplan.platform.dto.checkin;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 提交或更新一条手工打卡。
 *
 * <p>三个字段都可为空：允许只记体重、只记 RPE，或者先记后补。
 * 全空表示这条打卡没有内容，服务层会据此拒绝。</p>
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
@Data
public class CheckinRequest {

    /** 体重（千克）。 */
    @DecimalMin(value = "20.0", message = "体重不能小于20千克")
    @DecimalMax(value = "300.0", message = "体重不能大于300千克")
    private BigDecimal weightKg;

    /** 主观疲劳度 1-10。 */
    @Min(value = 1, message = "RPE 不能小于1")
    @Max(value = 10, message = "RPE 不能大于10")
    private Integer rpe;

    /** 备注。 */
    @Size(max = 512, message = "备注长度不能超过512")
    private String note;
}
