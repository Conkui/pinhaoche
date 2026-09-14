package com.pinhaoche.feature;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** “找拼友”（预打车）唯一配置行，和实时拼配置物理隔离。 */
@Entity
@Table(name = "find_friends_setting")
public class FindFriendsSetting {

    @Id
    private Byte id;
    private boolean enabled;
    private String announcement;
    private OffsetDateTime updatedAt;

    protected FindFriendsSetting() { }

    FindFriendsSetting(boolean enabled, String announcement, OffsetDateTime updatedAt) {
        this.id = 1;
        this.enabled = enabled;
        this.announcement = announcement;
        this.updatedAt = updatedAt;
    }

    void update(boolean enabled, String announcement, OffsetDateTime updatedAt) {
        this.enabled = enabled;
        this.announcement = announcement;
        this.updatedAt = updatedAt;
    }

    public boolean isEnabled() { return enabled; }
    public String getAnnouncement() { return announcement; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
