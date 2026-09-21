package com.trainingplan.platform.dto.sync;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 单个 Garmin 账号的同步状态。
 *
 * @param accountId        账号 ID
 * @param accountLabel     账号展示名（脱敏邮箱 + 站点）
 * @param authStatus       认证状态
 * @param syncEnabled      是否开启自动同步
 * @param lastSyncTime     账号记录的最近同步时间
 * @param lastSuccessTime  该账号最近一次成功任务的结束时间
 * @param lastFailureTime  该账号最近一次失败任务的结束时间
 * @param lastErrorMessage 该账号最近一次失败原因
 * @param lastStartDate    该账号最近一次任务的数据起始日期
 * @param lastEndDate      该账号最近一次任务的数据结束日期
 * @author gongxuesong
 * @date 2026-09-21
 */
public record AccountSyncStateDto(Long accountId,
                                  String accountLabel,
                                  String authStatus,
                                  Integer syncEnabled,
                                  LocalDateTime lastSyncTime,
                                  LocalDateTime lastSuccessTime,
                                  LocalDateTime lastFailureTime,
                                  String lastErrorMessage,
                                  LocalDate lastStartDate,
                                  LocalDate lastEndDate) {
}
