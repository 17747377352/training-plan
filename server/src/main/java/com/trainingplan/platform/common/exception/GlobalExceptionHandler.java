package com.trainingplan.platform.common.exception;

import com.trainingplan.platform.common.api.Result;
import com.trainingplan.platform.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * REST 接口全局异常处理器。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException exception) {
        return Result.failure(exception.getErrorCode(), exception.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public Result<Void> handleValidationException(Exception exception) {
        log.warn("请求参数校验失败: {}", exception.getMessage());
        return Result.failure(ErrorCode.PARAM_ERROR);
    }

    /**
     * 处理方法级鉴权失败。
     *
     * <p>{@code @PreAuthorize} 拒绝时抛出 {@link AccessDeniedException}，
     * 若不单独处理会被下面的兜底分支当作系统异常返回 HTTP 200，
     * 导致前端把「无权限」误判为「服务不可用」。</p>
     *
     * @param exception 鉴权异常
     * @return HTTP 403 与统一错误体
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDeniedException(AccessDeniedException exception) {
        log.warn("方法级鉴权失败: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.failure(ErrorCode.FORBIDDEN));
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpectedException(Exception exception) {
        log.error("未处理的系统异常", exception);
        return Result.failure(ErrorCode.SYSTEM_ERROR);
    }
}

