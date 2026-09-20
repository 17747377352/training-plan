package com.trainingplan.platform.common.exception;

import com.trainingplan.platform.common.api.Result;
import com.trainingplan.platform.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    /**
     * 处理未知路径。
     *
     * <p>若不单独处理，会被下面的兜底分支变成 HTTP 200 与「系统暂时不可用」，
     * 让调用方以为服务故障，而不是路径写错。</p>
     *
     * @param exception 资源未找到异常
     * @return HTTP 404 与统一错误体
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResourceFound(NoResourceFoundException exception) {
        log.warn("请求了不存在的路径: {}", exception.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.failure(ErrorCode.NOT_FOUND));
    }

    /**
     * 处理路径存在但方法不匹配的情况，例如对只支持 DELETE 的地址发 POST。
     *
     * @param exception 方法不支持异常
     * @return HTTP 405 与统一错误体
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception) {
        log.warn("请求方法不支持: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.failure(ErrorCode.METHOD_NOT_ALLOWED));
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpectedException(Exception exception) {
        log.error("未处理的系统异常", exception);
        return Result.failure(ErrorCode.SYSTEM_ERROR);
    }
}

