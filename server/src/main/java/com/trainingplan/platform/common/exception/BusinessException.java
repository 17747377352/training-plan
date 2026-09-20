package com.trainingplan.platform.common.exception;

import com.trainingplan.platform.common.error.ErrorCode;
import lombok.Getter;

/**
 * 可安全返回给调用方的业务异常。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}

