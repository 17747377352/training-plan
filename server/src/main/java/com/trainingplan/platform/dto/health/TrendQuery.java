package com.trainingplan.platform.dto.health;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 健康趋势日期查询条件。
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
@Data
public class TrendQuery {

    /** 开始日期（包含），缺省时为结束日期前 29 天。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;

    /** 结束日期（包含），缺省时为当天。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;
}
