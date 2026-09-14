package com.pinhaoche.feature;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** 实时拼唯一配置行（id=1）；独立表使运营开关不污染 Ride 领域表。 */
@Entity
@Table(name = "realtime_carpool_setting")
public class RealtimeCarpoolSetting {

    @Id
    private Byte id;
    private boolean enabled;
    private String announcement;
    private OffsetDateTime updatedAt;

    protected RealtimeCarpoolSetting() {
    }

    RealtimeCarpoolSetting(boolean enabled, String announcement, OffsetDateTime updatedAt) {
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
