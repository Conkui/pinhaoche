package com.pinhaoche.infrastructure.outbox;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** idempotency_record 表（V1__init.sql）：HTTP 幂等的最终保障。 */
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long actorId;
    private String idempotencyKey;
    private String operation;
    private String requestHash;
    private Long resourceId;
    private OffsetDateTime createdAt;
    private OffsetDateTime expiresAt;

    public IdempotencyRecord() {
    }

    public IdempotencyRecord(Long actorId, String idempotencyKey, String operation,
                             String requestHash, Long resourceId,
                             OffsetDateTime createdAt, OffsetDateTime expiresAt) {
        this.actorId = actorId;
        this.idempotencyKey = idempotencyKey;
        this.operation = operation;
        this.requestHash = requestHash;
        this.resourceId = resourceId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public Long getActorId() { return actorId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getOperation() { return operation; }
    public String getRequestHash() { return requestHash; }
    public Long getResourceId() { return resourceId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
}
