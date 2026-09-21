package com.trainingplan.platform.dto.activity;

import com.trainingplan.platform.common.api.PageParam;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 当前用户的活动列表查询条件。
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ActivityQuery extends PageParam {

    /** 开始日期（包含）。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;

    /** 结束日期（包含）。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;

    /** Garmin 稳定活动类型标识。 */
    @Size(max = 64, message = "活动类型长度不能超过64")
    private String typeKey;

    /** 活动名称关键字。 */
    @Size(max = 100, message = "关键字长度不能超过100")
    private String keyword;
}
