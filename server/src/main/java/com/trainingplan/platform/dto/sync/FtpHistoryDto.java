package com.trainingplan.platform.dto.sync;

/**
 * 采集器上报的一条骑行 FTP 记录。
 *
 * @param effectiveDate 生效日期
 * @param ftpWatts      功能阈值功率（瓦）
 * @author gongxuesong
 * @date 2026-09-21
 */
public record FtpHistoryDto(String effectiveDate, Integer ftpWatts) {
}
