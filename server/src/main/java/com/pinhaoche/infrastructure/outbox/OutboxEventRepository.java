package com.pinhaoche.infrastructure.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findTop100ByStatusOrderByIdAsc(String status);

    /**
     * 物理删除订单前撤销尚未投递或历史保留的订单事件，避免删除后异步消费者再处理已不存在的 Ride。
     * aggregate_type 一并限定，避免数值 ID 恰好相同的其他聚合被误删。
     */
    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.aggregateType = :aggregateType AND e.aggregateId = :aggregateId")
    int deleteByAggregateTypeAndAggregateId(@Param("aggregateType") String aggregateType,
                                            @Param("aggregateId") long aggregateId);
}
