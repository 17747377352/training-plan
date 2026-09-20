package com.trainingplan.platform.common.api;

import com.trainingplan.platform.common.error.ErrorCode;

/**
 * REST 接口统一返回结构。
 *
 * @param code    业务状态码
 * @param message 提示信息
 * @param data    响应数据
 * @param <T>     数据类型
 * @author gongxuesong
 * @date 2026-09-20
 */
public record Result<T>(Integer code, String message, T data) {

    public static <T> Result<T> success(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    public static Result<Void> failure(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static Result<Void> failure(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }
}

