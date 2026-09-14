package com.pinhaoche.common.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：所有错误都返回 openapi.yaml 定义的 ApiError 结构 { code, message }。
 * 状态码约定：容量/版本/状态迁移/幂等冲突 → 409；权限 → 403；校验 → 400；未登录 → 401；限流 → 429（RFC-010）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    public record ApiError(String code, String message) {
        public static ApiError of(ErrorCode code, String message) {
            return new ApiError(code.name(), message);
        }
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException e) {
        return ResponseEntity.status(switch (e.getCode()) {
            case CAPACITY_FULL, VERSION_CONFLICT, ILLEGAL_TRANSITION, IDEMPOTENCY_CONFLICT -> 409;
            case FORBIDDEN -> 403;
            case UNAUTHORIZED -> 401;
            case NOT_FOUND -> 404;
            case VALIDATION_ERROR -> 400;
            case RATE_LIMITED -> 429;
        }).body(ApiError.of(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst().orElse("参数校验失败");
        return ResponseEntity.badRequest().body(ApiError.of(ErrorCode.VALIDATION_ERROR, msg));
    }
}
