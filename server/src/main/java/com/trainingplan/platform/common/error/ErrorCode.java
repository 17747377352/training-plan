package com.trainingplan.platform.common.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 平台统一业务错误码。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    SUCCESS(200, "success"),
    PARAM_ERROR(40000, "请求参数错误"),
    UNAUTHORIZED(40100, "未登录或登录已失效"),
    FORBIDDEN(40300, "没有操作权限"),
    NOT_FOUND(40400, "请求的数据不存在"),
    BUSINESS_ERROR(40900, "业务处理失败"),
    GARMIN_AUTH_REQUIRED(46001, "Garmin账号需要重新认证"),
    GARMIN_RATE_LIMITED(46002, "Garmin接口请求过于频繁"),
    GARMIN_SYNC_ERROR(46003, "Garmin数据同步失败"),
    AI_SERVICE_ERROR(47001, "AI服务调用失败"),
    AI_TIMEOUT(47002, "AI服务响应超时"),
    SYSTEM_ERROR(50000, "系统暂时不可用");

    private final Integer code;
    private final String message;
}

