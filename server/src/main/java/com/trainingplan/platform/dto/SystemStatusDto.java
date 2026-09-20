package com.trainingplan.platform.dto;

import java.time.Instant;

/**
 * 系统运行状态响应。
 *
 * @param application 应用名称
 * @param status      运行状态
 * @param timestamp   服务端时间
 * @author gongxuesong
 * @date 2026-09-20
 */
public record SystemStatusDto(String application, String status, Instant timestamp) {
}

