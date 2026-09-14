package com.pinhaoche.infrastructure.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByActorIdAndIdempotencyKey(Long actorId, String idempotencyKey);

    /**
     * Ride 的 create/join/transit/import 都以 RideDto 作为幂等资源，因此可按 resourceId 回收。
     * operation 前缀是必要保护，避免不同领域实体使用相同数值主键时被连带删除。
     */
    @Modifying
    @Query("DELETE FROM IdempotencyRecord r WHERE r.resourceId = :rideId AND r.operation LIKE 'ride.%'")
    int deleteRideRecordsByResourceId(@Param("rideId") long rideId);
}
