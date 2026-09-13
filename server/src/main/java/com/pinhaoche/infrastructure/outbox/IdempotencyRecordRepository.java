package com.pinhaoche.infrastructure.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByActorIdAndIdempotencyKey(Long actorId, String idempotencyKey);
}
