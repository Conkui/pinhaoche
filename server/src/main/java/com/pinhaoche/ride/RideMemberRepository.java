package com.pinhaoche.ride;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RideMemberRepository extends JpaRepository<RideMember, Long> {

    boolean existsByRideIdAndUserId(long rideId, long userId);

    /**
     * 只统计仍未终态的订单成员。成员表没有“离开”语义，故 CLOSED/CANCELLED/EXPIRED
     * 才会释放该用户的并行同行名额；ARRIVED 仍属于尚未 CLOSED 的有效行程。
     */
    @Query("""
            SELECT COUNT(m) FROM RideMember m, Ride r
            WHERE m.rideId = r.id
              AND m.userId = :userId
              AND r.status IN :statuses
            """)
    long countByUserIdAndRideStatusIn(@Param("userId") long userId,
                                      @Param("statuses") List<RideStatus> statuses);

    /** 仅用于车主的"我的发布"联系人视图；成员顺序按加入时间稳定展示。 */
    List<RideMember> findByRideIdOrderByCreatedAtAsc(long rideId);

    /** 管理端物理清理订单时先删成员，避免 ride 外键约束阻止主记录删除。 */
    @Modifying
    @Query("DELETE FROM RideMember m WHERE m.rideId = :rideId")
    int deleteByRideId(@Param("rideId") long rideId);
}
