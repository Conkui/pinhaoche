package com.pinhaoche.common.api;

/**
 * 与 openapi.yaml ApiError.code 一一对应的错误码，禁止私自新增枚举值。
 * 新增错误码属于公共契约变更，必须走 RFC。
 */
public enum ErrorCode {
    CAPACITY_FULL,
    VERSION_CONFLICT,
    ILLEGAL_TRANSITION,
    FORBIDDEN,
    IDEMPOTENCY_CONFLICT,
    VALIDATION_ERROR,
    NOT_FOUND,
    UNAUTHORIZED,
    /** 接口限流（RFC-010）：登录按 IP 20/分钟、已登录用户 120/分钟，超限返回 HTTP 429。 */
    RATE_LIMITED
}
