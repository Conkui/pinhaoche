package com.pinhaoche.chat;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** chat_session 表（V1__init.sql）：同一 Ride 下两人唯一会话，(user_low_id, user_high_id) 排序去重。 */
@Entity
@Table(name = "chat_session")
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long rideId;
    private Long userLowId;
    private Long userHighId;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public ChatSession() {
    }

    public ChatSession(long rideId, long userLowId, long userHighId, OffsetDateTime now) {
        this.rideId = rideId;
        this.userLowId = userLowId;
        this.userHighId = userHighId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public Long getRideId() { return rideId; }
    public Long getUserLowId() { return userLowId; }
    public Long getUserHighId() { return userHighId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public boolean hasMember(long userId) {
        return userId == userLowId || userId == userHighId;
    }

    /** 对端用户 id。 */
    public long peerOf(long userId) {
        return userId == userLowId ? userHighId : userLowId;
    }
}
