package com.pinhaoche.infrastructure.outbox;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * outbox_event 表（V1__init.sql）：业务事务内与业务数据一起写入，
 * 由 OutboxPublisher 异步投递 RabbitMQ（规范 §22 Outbox Pattern）。
 * payload 用 String 承载 JSON，列类型为 JSON，MySQL 自动校验合法性。
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String aggregateType;
    private Long aggregateId;
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime publishedAt;

    public OutboxEvent() {
    }

    public OutboxEvent(String aggregateType, Long aggregateId, String eventType, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = "PENDING";
        this.createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public Long getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }

    public void markPublished() {
        this.status = "PUBLISHED";
        this.publishedAt = OffsetDateTime.now();
    }
}
