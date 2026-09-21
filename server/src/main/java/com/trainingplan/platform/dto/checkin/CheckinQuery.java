package com.trainingplan.platform.dto.checkin;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 手工打卡查询条件。
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
@Data
public class CheckinQuery {

    /** 开始日期（包含）。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;

    /** 结束日期（包含）。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;
}
