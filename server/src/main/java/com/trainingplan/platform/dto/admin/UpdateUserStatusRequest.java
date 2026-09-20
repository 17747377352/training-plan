package com.trainingplan.platform.dto.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 修改平台用户状态请求。
 *
 * @param status 目标状态：0 禁用，1 启用
 * @author gongxuesong
 * @date 2026-09-20
 */
public record UpdateUserStatusRequest(
        @NotNull(message = "状态不能为空")
        @Min(value = 0, message = "状态只能为0或1")
        @Max(value = 1, message = "状态只能为0或1")
        Integer status) {
}
