package com.pinhaoche.ride;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RideMemberRepository extends JpaRepository<RideMember, Long> {

    boolean existsByRideIdAndUserId(long rideId, long userId);
}
