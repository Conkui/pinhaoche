package com.pinhaoche.ride;

/**
 * Ride 状态枚举，与 openapi.yaml RideStatus / docs/STATE_MACHINE.md 完全一致。
 * 迁移规则只允许通过 RideStateMachine 校验，禁止在业务代码里散落 if(status==...)。
 */
public enum RideStatus {
    DRAFT, PUBLISHED, MATCHING, BOOKED, IN_PROGRESS, ARRIVED, CLOSED, CANCELLED, EXPIRED
}
