package com.pinhaoche.ride;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RideMemberRepository extends JpaRepository<RideMember, Long> {

    boolean existsByRideIdAndUserId(long rideId, long userId);

    /** 仅用于车主的"我的发布"联系人视图；成员顺序按加入时间稳定展示。 */
    List<RideMember> findByRideIdOrderByCreatedAtAsc(long rideId);
}
