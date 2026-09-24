package com.trainingplan.platform.dto.sync;

/**
 * 上报的一条阈值心率（乳酸阈值心率）记录。
 *
 * <p>Garmin 只提供跑步系列，骑行系列实测为空，所以 {@code series} 必须带上，
 * 否则两个系列会互相覆盖。</p>
 *
 * @param effectiveDate 生效日期
 * @param series        系列：{@code RUNNING} / {@code CYCLING}
 * @param heartRate     阈值心率（bpm）
 * @param source        来源；为空按 {@code GARMIN} 处理
 * @author gongxuesong
 * @date 2026-09-24
 */
public record ThresholdHrDto(String effectiveDate, String series, Integer heartRate, String source) {
}
