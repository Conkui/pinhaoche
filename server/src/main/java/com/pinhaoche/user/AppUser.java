package com.pinhaoche.user;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** app_user 表（V1__init.sql）。 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String openid;
    private String nickname;
    private String avatar;
    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public AppUser() {
    }

    public AppUser(String openid, OffsetDateTime now) {
        this.openid = openid;
        // 默认昵称：V1 不做资料编辑页，登录即生成占位昵称，避免空昵称在聊天/列表展示破版
        this.nickname = "同学" + Long.toHexString(System.currentTimeMillis() & 0xFFFF);
        this.status = "ACTIVE";
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public String getOpenid() { return openid; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
