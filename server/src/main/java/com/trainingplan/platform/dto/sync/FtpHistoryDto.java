package com.trainingplan.platform.dto.sync;

/**
 * 上报的一条骑行 FTP 记录。
 *
 * <p>{@code source} 允许为空：采集器走 Garmin 接口取到的 FTP 不带这个字段，落库时按
 * {@code GARMIN} 处理；本机浏览器上传拿不到 FTP 接口，只能按 Garmin 自己的 IF 定义
 * （{@code IF = NP / FTP}）反解，必须显式标成 {@code DERIVED}，不能让它看起来像
 * Garmin 报出来的值。</p>
 *
 * @param effectiveDate 生效日期
 * @param ftpWatts      功能阈值功率（瓦）
 * @param source        来源：{@code GARMIN}（接口测得）/ {@code DERIVED}（由 NP/IF 反解）
 *                      / {@code MANUAL}（手工录入）；为空按 {@code GARMIN} 处理
 * @author gongxuesong
 * @date 2026-09-24
 */
public record FtpHistoryDto(String effectiveDate, Integer ftpWatts, String source) {
}
