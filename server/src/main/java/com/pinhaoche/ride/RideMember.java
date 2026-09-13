package com.pinhaoche.ride;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * ride_member 表（V1__init.sql）：复合主键 (ride_id, user_id) 天然防止重复加入。
 * 为什么用 @IdClass：冻结表结构没有自增 id 列，实体必须与表结构精确对齐。
 */
@Entity
@Table(name = "ride_member")
@IdClass(RideMember.PK.class)
public class RideMember {

    @Id
    private Long rideId;

    @Id
    private Long userId;

    /** PENDING / CONFIRMED */
    private String status;
    private OffsetDateTime createdAt;

    public RideMember() {
    }

    public RideMember(long rideId, long userId, String status, OffsetDateTime now) {
        this.rideId = rideId;
        this.userId = userId;
        this.status = status;
        this.createdAt = now;
    }

    /** 复合主键：必须可序列化且实现 equals/hashCode。 */
    public static class PK implements Serializable {
        private Long rideId;
        private Long userId;

        public PK() {
        }

        public PK(long rideId, long userId) {
            this.rideId = rideId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof PK pk)) {
                return false;
            }
            return Objects.equals(rideId, pk.rideId) && Objects.equals(userId, pk.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(rideId, userId);
        }
    }

    public Long getRideId() { return rideId; }
    public Long getUserId() { return userId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
