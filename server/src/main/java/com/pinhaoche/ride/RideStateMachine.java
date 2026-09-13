package com.pinhaoche.ride;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

import static com.pinhaoche.ride.RideStatus.ARRIVED;
import static com.pinhaoche.ride.RideStatus.BOOKED;
import static com.pinhaoche.ride.RideStatus.CLOSED;
import static com.pinhaoche.ride.RideStatus.DRAFT;
import static com.pinhaoche.ride.RideStatus.IN_PROGRESS;
import static com.pinhaoche.ride.RideStatus.MATCHING;
import static com.pinhaoche.ride.RideStatus.PUBLISHED;

/**
 * Ride 状态机（docs/STATE_MACHINE.md）：集中校验所有状态迁移，唯一合法迁移表。
 * 加入成员仅允许 PUBLISHED/MATCHING/BOOKED —— 该约束在 RideService 的 SQL 条件里保证，
 * 本机负责"显式 transitions 接口"的合法性。
 */
@Component
public class RideStateMachine {

    private static final Map<RideStatus, Set<RideStatus>> ALLOWED = Map.of(
            DRAFT, Set.of(PUBLISHED, RideStatus.CANCELLED),
            PUBLISHED, Set.of(MATCHING, BOOKED, RideStatus.CANCELLED, RideStatus.EXPIRED),
            MATCHING, Set.of(BOOKED, RideStatus.CANCELLED, RideStatus.EXPIRED),
            BOOKED, Set.of(IN_PROGRESS, RideStatus.CANCELLED),
            IN_PROGRESS, Set.of(ARRIVED, RideStatus.CANCELLED),
            ARRIVED, Set.of(CLOSED),
            // 终态不可恢复
            CLOSED, Set.of(), RideStatus.CANCELLED, Set.of(), RideStatus.EXPIRED, Set.of());

    public boolean canTransit(RideStatus from, RideStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /** 非法迁移抛 ILLEGAL_TRANSITION（openapi ApiError）。 */
    public void assertTransit(RideStatus from, RideStatus to) {
        if (!canTransit(from, to)) {
            throw new com.pinhaoche.common.api.ApiException(
                    com.pinhaoche.common.api.ErrorCode.ILLEGAL_TRANSITION,
                    "状态不允许从 %s 迁移到 %s".formatted(from, to));
        }
    }

    /** 可加入成员的状态集合（docs/STATE_MACHINE.md）。 */
    public static Set<RideStatus> JOINABLE = Set.of(PUBLISHED, MATCHING, BOOKED);
}
