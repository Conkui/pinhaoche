package com.pinhaoche.feedback;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** feedback 表（V2__feedback.sql，RFC-006）。 */
@Entity
@Table(name = "feedback")
public class Feedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private String content;
    private OffsetDateTime createdAt;

    public Feedback() {
    }

    public Feedback(long userId, String content, OffsetDateTime now) {
        this.userId = userId;
        this.content = content;
        this.createdAt = now;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getContent() { return content; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
