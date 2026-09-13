package com.pinhaoche.ride;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pinhaoche.infrastructure.outbox.OutboxEvent;
import com.pinhaoche.infrastructure.outbox.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 预打车过期任务（用户红线：时间效益）。
 * 出发时间已过仍处于 PUBLISHED/MATCHING 的单 → 状态机合法迁移到 EXPIRED，
 * 保证"今天打开看不到昨天的拼车单"不仅靠列表过滤，数据本身也收敛到终态。
 * 幂等性：迁移走乐观锁条件更新，重复调度不会二次迁移；迁移失败（并发竞争）留到下一轮。
 */
@Component
public class RideExpireJob {

    private static final Logger log = LoggerFactory.getLogger(RideExpireJob.class);

    private final RideRepository rideRepository;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public RideExpireJob(RideRepository rideRepository, OutboxEventRepository outboxRepository,
                         ObjectMapper objectMapper) {
        this.rideRepository = rideRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void expireOverdueRides() {
        var overdue = rideRepository.findByStatusInAndDepartTimeBefore(
                List.of(RideStatus.PUBLISHED, RideStatus.MATCHING), OffsetDateTime.now());
        for (Ride ride : overdue) {
            int updated = rideRepository.transitAtomically(
                    ride.getId(), ride.getVersion(), ride.getStatus(), RideStatus.EXPIRED);
            if (updated == 1) {
                try {
                    outboxRepository.save(new OutboxEvent("ride", ride.getId(), "RideExpired",
                            objectMapper.writeValueAsString(Map.of(
                                    "rideId", ride.getId(), "from", ride.getStatus().name(),
                                    "to", RideStatus.EXPIRED.name()))));
                } catch (Exception e) {
                    throw new IllegalStateException("RideExpired 事件序列化失败", e);
                }
            } else {
                log.info("过期迁移竞争失败，留待下一轮 rideId={}", ride.getId());
            }
        }
    }
}
