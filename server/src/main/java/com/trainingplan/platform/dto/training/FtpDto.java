package com.trainingplan.platform.dto.training;

import java.time.LocalDate;

/**
 * 一条骑行 FTP 记录。
 *
 * @param effectiveDate 生效日期
 * @param ftpWatts      功能阈值功率（瓦）
 * @param source        来源
 * @author gongxuesong
 * @date 2026-09-21
 */
public record FtpDto(LocalDate effectiveDate, Integer ftpWatts, String source) {
}
