package com.trainingplan.platform.dto.garmin;

import com.trainingplan.platform.dto.sync.SyncIngestRequest;

import java.time.LocalDate;

/**
 * 浏览器采集后的一个上传批次，最多覆盖连续 31 天。
 *
 * @param startDate 数据起始日，含当日
 * @param endDate 数据结束日，含当日
 * @param data 白名单业务数据，不包含原始响应、GPS 或浏览器会话
 * @author gongxuesong
 * @date 2026-09-24
 */
public record BrowserUploadRequest(LocalDate startDate, LocalDate endDate, SyncIngestRequest data) {
}
