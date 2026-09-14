package com.pinhaoche.admin;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * 管理端删除指令的不可变审计记录。
 * 不改 Ride.status：状态机和公共 Ride DTO 已冻结，读取侧是否隐藏由 Ride 模块显式接入本表决定。
 */
@Entity
@Table(name = "admin_ride_suppression")
class AdminRideSuppression {
    @Id
    private Long rideId;
    private OffsetDateTime deletedAt;

    protected AdminRideSuppression() {
    }

    AdminRideSuppression(long rideId, OffsetDateTime deletedAt) {
        this.rideId = rideId;
        this.deletedAt = deletedAt;
    }

    long getRideId() {
        return rideId;
    }

    OffsetDateTime getDeletedAt() {
        return deletedAt;
    }
}
