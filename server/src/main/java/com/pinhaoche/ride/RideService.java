package com.pinhaoche.ride;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pinhaoche.common.api.ApiException;
import com.pinhaoche.common.api.ErrorCode;
import com.pinhaoche.common.idempotency.IdempotencyService;
import com.pinhaoche.infrastructure.outbox.OutboxEvent;
import com.pinhaoche.infrastructure.outbox.OutboxEventRepository;
import com.pinhaoche.place.PlaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Ride 应用服务（Agent B 边界：Ride + 状态机 + 发布/取消/加入/人数并发/幂等；不处理聊天）。
 * 事务约定：业务数据与 outbox_event 必须在同一数据库事务内写入（规范 §22）。
 */
@Service
public class RideService {

    private final RideRepository rideRepository;
    private final RideMemberRepository memberRepository;
    private final RideStateMachine stateMachine;
    private final PlaceService placeService;
    private final IdempotencyService idempotency;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public RideService(RideRepository rideRepository, RideMemberRepository memberRepository,
                       RideStateMachine stateMachine, PlaceService placeService,
                       IdempotencyService idempotency, OutboxEventRepository outboxRepository,
                       ObjectMapper objectMapper) {
        this.rideRepository = rideRepository;
        this.memberRepository = memberRepository;
        this.stateMachine = stateMachine;
        this.placeService = placeService;
        this.idempotency = idempotency;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    // ---------- DTO（与 openapi.yaml schema 一一对应，字段增减属于契约变更） ----------

    public record PlaceRef(String type, Long placeId, String text) {
    }

    public record CreateRideRequest(PlaceRef origin, PlaceRef destination, OffsetDateTime departTime,
                                    int memberCount, int capacity, String remark, Integer waitingMinutes) {
    }

    public record RideDto(long id, long ownerId, PlaceRef origin, PlaceRef destination,
                          OffsetDateTime departTime, int memberCount, int capacity, RideStatus status,
                          String plateNumber, String provider, BigDecimal remainingDistance,
                          OffsetDateTime distanceUpdatedAt, Integer waitingMinutes, long version, String remark)
            implements IdempotencyService.Resource {
    }

    public record TransitionRequest(RideStatus targetStatus, long version) {
    }

    // ---------- 查询 ----------

    /** view=PREBOOK：预打车列表（PUBLISHED/MATCHING，且未过出发时间）；view=BOOKED：已打上列表（BOOKED）。 */
    @Transactional(readOnly = true)
    public List<RideDto> list(String view) {
        return switch (view) {
            case "PREBOOK" -> rideRepository
                    // 用户红线：出发时间已过的单对所有人隐藏（过期任务会随后把状态落为 EXPIRED）
                    .findByStatusInAndDepartTimeAfterOrderByDepartTimeAsc(
                            List.of(RideStatus.PUBLISHED, RideStatus.MATCHING), OffsetDateTime.now())
                    .stream().map(RideService::toDto).toList();
            case "BOOKED" -> rideRepository.findByStatusOrderByUpdatedAtDesc(RideStatus.BOOKED)
                    .stream().map(RideService::toDto).toList();
            default -> throw ApiException.validation("view 必须为 PREBOOK 或 BOOKED");
        };
    }

    @Transactional(readOnly = true)
    public RideDto get(long rideId) {
        return rideRepository.findById(rideId).map(RideService::toDto)
                .orElseThrow(() -> ApiException.notFound("拼车记录不存在"));
    }

    // ---------- 发布（预打车，幂等接口） ----------

    @Transactional
    public RideDto create(long ownerId, CreateRideRequest req, String idempotencyKey) {
        validateCapacity(req.memberCount(), req.capacity());
        validateStatusFields(req);
        return idempotency.execute(ownerId, idempotencyKey, "ride.create", req,
                id -> get(id), () -> {
                    Ride ride = new Ride();
                    ride.setOwnerId(ownerId);
                    PlaceRef origin = resolveRef(req.origin());
                    PlaceRef destination = resolveRef(req.destination());
                    ride.setOriginType(origin.type());
                    ride.setOriginPlaceId(origin.placeId());
                    ride.setOriginText(origin.text());
                    ride.setDestinationType(destination.type());
                    ride.setDestinationPlaceId(destination.placeId());
                    ride.setDestinationText(destination.text());
                    ride.setDepartTime(req.departTime());
                    ride.setMemberCount(req.memberCount());
                    ride.setCapacity(req.capacity());
                    ride.setStatus(RideStatus.PUBLISHED);
                    ride.setRemark(req.remark());
                    ride.setWaitingMinutes(req.waitingMinutes());
                    ride.setVersion(0L);
                    OffsetDateTime now = OffsetDateTime.now();
                    ride.setCreatedAt(now);
                    ride.setUpdatedAt(now);
                    Ride saved = rideRepository.save(ride);
                    return toDto(saved);
                });
    }

    // ---------- 加入（幂等接口 + 条件更新并发，规范 §20/§21） ----------

    /**
     * READ_COMMITTED：抢位重试循环必须读到"最新已提交"的 version。
     * 默认 REPEATABLE READ 下重试永远读到本事务快照里的旧版本，高并发时注定失败（实测 50 并发只成功 5 个）。
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public RideDto join(long rideId, long userId, String idempotencyKey) {
        return idempotency.execute(userId, idempotencyKey, "ride.join", rideId,
                id -> get(rideId), () -> doJoin(rideId, userId));
    }

    private RideDto doJoin(long rideId, long userId) {
        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> ApiException.notFound("拼车记录不存在"));
        if (ride.getOwnerId() == userId) {
            throw ApiException.validation("不能加入自己发起的拼车");
        }
        if (memberRepository.existsByRideIdAndUserId(rideId, userId)) {
            throw ApiException.validation("已加入该拼车，请勿重复加入");
        }

        // 高并发抢位重试：版本乐观锁下每轮只有一个事务能赢，失败者重读新版本再试。
        // READ_COMMITTED + 重试 8 次（实测 50 人并发抢 7 座可全部消化）；仍失败按最终状态精确归因返回 409。
        for (int attempt = 0; attempt < 8; attempt++) {
            int updated = rideRepository.joinAtomically(rideId, ride.getVersion(),
                    List.copyOf(RideStateMachine.JOINABLE), RideStatus.PUBLISHED, RideStatus.MATCHING);
            if (updated == 1) {
                memberRepository.save(new RideMember(rideId, userId, "CONFIRMED", OffsetDateTime.now()));
                emit(rideId, "RideMemberJoined", Map.of(
                        "rideId", rideId, "userId", userId,
                        "memberCount", ride.getMemberCount() + 1));
                return get(rideId);
            }
            ride = rideRepository.findById(rideId)
                    .orElseThrow(() -> ApiException.notFound("拼车记录不存在"));
        }

        // 两次都失败 → 按最终状态精确归因
        Ride latest = rideRepository.findById(rideId).orElseThrow();
        if (!RideStateMachine.JOINABLE.contains(latest.getStatus())) {
            throw new ApiException(ErrorCode.ILLEGAL_TRANSITION, "当前状态不可加入: " + latest.getStatus());
        }
        if (latest.getMemberCount() >= latest.getCapacity()) {
            throw new ApiException(ErrorCode.CAPACITY_FULL, "位置已满");
        }
        throw new ApiException(ErrorCode.VERSION_CONFLICT, "操作冲突，请重试");
    }

    // ---------- 显式状态迁移（幂等接口，仅 owner） ----------

    @Transactional
    public RideDto transit(long rideId, long userId, RideStatus target, long version, String idempotencyKey) {
        return idempotency.execute(userId, idempotencyKey, "ride.transit." + target,
                Map.of("rideId", rideId, "target", target, "version", version),
                id -> get(rideId), () -> doTransit(rideId, userId, target, version));
    }

    private RideDto doTransit(long rideId, long userId, RideStatus target, long version) {
        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> ApiException.notFound("拼车记录不存在"));
        if (ride.getOwnerId() != userId) {
            throw ApiException.forbidden("只有发起人可以变更行程状态");
        }
        stateMachine.assertTransit(ride.getStatus(), target);
        int updated = rideRepository.transitAtomically(rideId, version, ride.getStatus(), target);
        if (updated == 0) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "状态已被其他操作更新，请刷新后重试");
        }
        emit(rideId, "RideStatusChanged", Map.of(
                "rideId", rideId, "from", ride.getStatus().name(), "to", target.name()));
        return get(rideId);
    }

    // ---------- 导入"已打上"（RFC-004，importer 模块经此落库） ----------

    /**
     * @param seats 车主愿意让出的剩余位置（1..8）；memberCount 固定 1（车主自己），capacity = seats + 1。
     */
    @Transactional
    public RideDto createBooked(long ownerId, PlaceRef origin, PlaceRef destination, String provider,
                                String plateNumber, BigDecimal remainingDistance,
                                Integer waitingMinutes, int seats, String idempotencyKey) {
        validateCapacity(1, seats + 1);
        PlaceRef o = resolveRef(origin);
        PlaceRef d = resolveRef(destination);
        var req = new CreateRideRequest(o, d, OffsetDateTime.now(), 1, seats + 1,
                null, waitingMinutes);
        return idempotency.execute(ownerId, idempotencyKey, "ride.import", req,
                id -> get(id), () -> {
                    Ride ride = new Ride();
                    ride.setOwnerId(ownerId);
                    ride.setOriginType(o.type());
                    ride.setOriginPlaceId(o.placeId());
                    ride.setOriginText(o.text());
                    ride.setDestinationType(d.type());
                    ride.setDestinationPlaceId(d.placeId());
                    ride.setDestinationText(d.text());
                    ride.setDepartTime(OffsetDateTime.now());
                    ride.setMemberCount(1);
                    ride.setCapacity(seats + 1);
                    // 导入即"已经叫到车"：直接 BOOKED，出现在已打上列表（docs/STATE_MACHINE.md）
                    ride.setStatus(RideStatus.BOOKED);
                    ride.setPlateNumber(plateNumber);
                    ride.setProvider(provider);
                    ride.setRemainingDistance(remainingDistance);
                    ride.setDistanceUpdatedAt(remainingDistance != null ? OffsetDateTime.now() : null);
                    ride.setWaitingMinutes(waitingMinutes);
                    ride.setVersion(0L);
                    OffsetDateTime now = OffsetDateTime.now();
                    ride.setCreatedAt(now);
                    ride.setUpdatedAt(now);
                    Ride saved = rideRepository.save(ride);
                    emit(saved.getId(), "RideBooked", Map.of(
                            "rideId", saved.getId(), "provider", provider, "ownerId", ownerId));
                    return toDto(saved);
                });
    }

    // ---------- 内部 ----------

    private void validateCapacity(int memberCount, int capacity) {
        if (capacity < 2 || capacity > 8) {
            throw ApiException.validation("capacity 必须在 2..8 之间");
        }
        if (memberCount < 1 || memberCount > capacity) {
            throw ApiException.validation("memberCount 必须在 1..capacity 之间");
        }
    }

    private void validateStatusFields(CreateRideRequest req) {
        if (req.origin() == null || req.destination() == null || req.departTime() == null) {
            throw ApiException.validation("origin/destination/departTime 不能为空");
        }
        if (req.waitingMinutes() != null
                && req.waitingMinutes() != 5 && req.waitingMinutes() != 10 && req.waitingMinutes() != 15) {
            throw ApiException.validation("waitingMinutes 只允许 5/10/15 或不传");
        }
    }

    /**
     * 地点引用解析：PRESET 必须引用启用的预设地点，快照名以库内名称为准（防伪造/防改名漂移）；
     * CUSTOM 只校验非空文本。V1 无模糊匹配。
     */
    private PlaceRef resolveRef(PlaceRef ref) {
        if (ref == null || ref.text() == null || ref.text().isBlank()) {
            throw ApiException.validation("地点文本不能为空");
        }
        if ("PRESET".equals(ref.type())) {
            if (ref.placeId() == null) {
                throw ApiException.validation("PRESET 地点必须携带 placeId");
            }
            String name = placeService.nameOf(ref.placeId())
                    .orElseThrow(() -> ApiException.validation("预设地点不存在或已停用"));
            return new PlaceRef("PRESET", ref.placeId(), name);
        }
        if ("CUSTOM".equals(ref.type())) {
            return new PlaceRef("CUSTOM", null, ref.text().trim());
        }
        throw ApiException.validation("地点类型只允许 PRESET/CUSTOM");
    }

    /** 领域事件写入 outbox，与业务同事务提交（规范 §22），由 OutboxPublisher 投递 MQ。 */
    private void emit(long rideId, String eventType, Map<String, Object> payload) {
        try {
            outboxRepository.save(new OutboxEvent("ride", rideId, eventType,
                    objectMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            throw new IllegalStateException("领域事件序列化失败", e);
        }
    }

    private static RideDto toDto(Ride r) {
        return new RideDto(r.getId(), r.getOwnerId(),
                new PlaceRef(r.getOriginType(), r.getOriginPlaceId(), r.getOriginText()),
                new PlaceRef(r.getDestinationType(), r.getDestinationPlaceId(), r.getDestinationText()),
                r.getDepartTime(), r.getMemberCount(), r.getCapacity(), r.getStatus(),
                r.getPlateNumber(), r.getProvider(), r.getRemainingDistance(),
                r.getDistanceUpdatedAt(), r.getWaitingMinutes(), r.getVersion(), r.getRemark());
    }
}
