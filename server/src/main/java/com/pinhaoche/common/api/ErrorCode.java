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
    UNAUTHORIZED
}
