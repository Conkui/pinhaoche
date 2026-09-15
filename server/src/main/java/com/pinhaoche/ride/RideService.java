package com.pinhaoche.ride;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pinhaoche.common.api.ApiException;
import com.pinhaoche.common.api.ErrorCode;
import com.pinhaoche.common.idempotency.IdempotencyService;
import com.pinhaoche.feature.FeatureAvailabilityService;
import com.pinhaoche.infrastructure.outbox.OutboxEvent;
import com.pinhaoche.infrastructure.outbox.OutboxEventRepository;
import com.pinhaoche.place.PlaceService;
import com.pinhaoche.user.UserContactService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Ride 应用服务（Agent B 边界：Ride + 状态机 + 发布/取消/加入/人数并发/幂等；不处理聊天）。
 * 事务约定：业务数据与 outbox_event 必须在同一数据库事务内写入（规范 §22）。
 */
@Service
public class RideService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<RideStatus> PENDING_COMPANION_STATUSES = List.of(
            RideStatus.PUBLISHED, RideStatus.MATCHING);
    /** 非终态均尚占用“同时加入”的名额，和现有状态机的终态定义保持一致。 */
    private static final List<RideStatus> ACTIVE_MEMBER_RIDE_STATUSES = List.of(
            RideStatus.PUBLISHED, RideStatus.MATCHING, RideStatus.BOOKED,
            RideStatus.IN_PROGRESS, RideStatus.ARRIVED);
    private static final int MAX_PENDING_RIDES = 3;
    private static final int MAX_ACTIVE_JOINED_RIDES = 3;
    private static final String PENDING_RIDE_LIMIT_MESSAGE = "当前委托较多，请完成或下架后再发布";

    private final RideRepository rideRepository;
    private final RideMemberRepository memberRepository;
    private final RideStateMachine stateMachine;
    private final PlaceService placeService;
    private final IdempotencyService idempotency;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final UserContactService userContactService;
    private final FeatureAvailabilityService featureAvailabilityService;

    public RideService(RideRepository rideRepository, RideMemberRepository memberRepository,
                       RideStateMachine stateMachine, PlaceService placeService,
                       IdempotencyService idempotency, OutboxEventRepository outboxRepository,
                       ObjectMapper objectMapper, UserContactService userContactService,
                       FeatureAvailabilityService featureAvailabilityService) {
        this.rideRepository = rideRepository;
        this.memberRepository = memberRepository;
        this.stateMachine = stateMachine;
        this.placeService = placeService;
        this.idempotency = idempotency;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.userContactService = userContactService;
        this.featureAvailabilityService = featureAvailabilityService;
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

    /** RFC-012 最小联系人卡：仅车主的我的发布/详情响应可携带，绝不含 openid 等直接身份信息。 */
    public record MemberContactDto(long userId, String nickname, String avatar) {
    }

    public record TransitionRequest(RideStatus targetStatus, long version) {
    }

    // ---------- 查询 ----------

    /** view=PREBOOK：预打车列表（PUBLISHED/MATCHING，且未过出发时间）；view=BOOKED：已打上列表（BOOKED）。 */
    @Transactional(readOnly = true)
    public List<RideDto> list(String view) {
        return switch (view) {
            case "PREBOOK" -> {
                // 找拼友关闭后服务端同样拒绝返回预打车数据，不能只依赖小程序公告页。
                featureAvailabilityService.requireFindFriendsEnabled();
                List<Ride> rides = rideRepository
                        // 用户红线：出发时间已过的单对所有人隐藏（过期任务会随后把状态落为 EXPIRED）
                        .findByStatusInAndDepartTimeAfterOrderByDepartTimeAsc(
                                List.of(RideStatus.PUBLISHED, RideStatus.MATCHING), OffsetDateTime.now());
                yield rides.stream().map(RideService::toDto).toList();
            }
            case "BOOKED" -> {
                // 实时拼独立控制“我已打上车”的供给和列表，不受找拼友开关影响。
                featureAvailabilityService.requireRealtimeCarpoolEnabled();
                yield rideRepository.findByStatusOrderByUpdatedAtDesc(RideStatus.BOOKED).stream()
                        .map(RideService::toDto).toList();
            }
            default -> throw ApiException.validation("view 必须为 PREBOOK 或 BOOKED");
        };
    }

    @Transactional(readOnly = true)
    public RideDto get(long rideId) {
        return rideRepository.findById(rideId).map(RideService::toDto)
                .orElseThrow(() -> ApiException.notFound("拼车记录不存在"));
    }

    /**
     * 管理端物理删除前的存在性校验边界。它刻意不把 repository 暴露给 admin 模块。
     */
    @Transactional(readOnly = true)
    public void assertExistsForAdmin(long rideId) {
        if (!rideRepository.existsById(rideId)) {
            throw ApiException.notFound("拼车记录不存在");
        }
    }

    /**
     * 管理端物理删除的并发屏障。锁会保持到 AdminRideDeletionService 的外层事务提交，
     * 使加入、状态迁移等更新在订单删掉后只能回滚/报不存在，不能留下孤儿 Outbox。
     */
    @Transactional(readOnly = true)
    public void lockForAdminDeletion(long rideId) {
        rideRepository.findByIdForAdminDeletion(rideId)
                .orElseThrow(() -> ApiException.notFound("拼车记录不存在"));
    }

    /** 详情保持既有冻结 Ride DTO；联系人由 RFC-012 的 owner-only 独立端点提供。 */
    @Transactional(readOnly = true)
    public RideDto getForViewer(long rideId, long viewerId) {
        return get(rideId);
    }

    /**
     * 聊天会话的订单关系门禁。公开列表中的用户仍可联系该单发布者；但只有发布者能联系
     * 已实际加入本订单的成员，避免客户端伪造 peerUserId 后任意查看或骚扰其他用户。
     */
    @Transactional(readOnly = true)
    public void assertChatPeerAllowed(long rideId, long requesterId, long peerUserId) {
        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> ApiException.notFound("拼车记录不存在"));
        if (requesterId == ride.getOwnerId()) {
            if (!memberRepository.existsByRideIdAndUserId(rideId, peerUserId)) {
                throw ApiException.forbidden("只能联系已加入该订单的拼友");
            }
            return;
        }
        if (peerUserId != ride.getOwnerId()) {
            throw ApiException.forbidden("非发布者只能联系该订单发布者");
        }
    }

    /** RFC-008：我的发布（含已取消/过期/结束），供"我的发布"管理页使用。 */
    @Transactional(readOnly = true)
    public List<RideDto> listMine(long ownerId) {
        return rideRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId).stream().map(RideService::toDto).toList();
    }

    /** RFC-012：仅订单 owner 可取最小联系人卡，不扩充冻结的 Ride DTO。 */
    @Transactional(readOnly = true)
    public List<MemberContactDto> listMemberContacts(long rideId, long ownerId) {
        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> ApiException.notFound("拼车记录不存在"));
        if (ride.getOwnerId() != ownerId) {
            throw ApiException.forbidden("只有发布者可以查看已加入的拼友");
        }
        return memberContacts(rideId);
    }

    // ---------- 发布（预打车，幂等接口） ----------

    @Transactional
    public RideDto create(long ownerId, CreateRideRequest req, String idempotencyKey) {
        featureAvailabilityService.requireFindFriendsEnabled();
        validateCapacity(req.memberCount(), req.capacity());
        validateStatusFields(req);
        return idempotency.execute(ownerId, idempotencyKey, "ride.create", req,
                id -> get(id), () -> withUserLimitLock(ownerId, () -> {
                    assertPendingRideLimit(ownerId);
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
                }));
    }

    // ---------- 加入（幂等接口 + 条件更新并发，规范 §20/§21） ----------

    /**
     * READ_COMMITTED：抢位重试循环必须读到"最新已提交"的 version。
     * 默认 REPEATABLE READ 下重试永远读到本事务快照里的旧版本，高并发时注定失败（实测 50 并发只成功 5 个）。
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public RideDto join(long rideId, long userId, String idempotencyKey) {
        featureAvailabilityService.requireFindFriendsEnabled();
        return idempotency.execute(userId, idempotencyKey, "ride.join", rideId,
                id -> get(rideId), () -> withUserLimitLock(userId, () -> doJoin(rideId, userId)));
    }

    /** 包级可见以便针对"成功才写 Outbox"不变量做无需 Spring 上下文的单元测试。 */
    RideDto doJoin(long rideId, long userId) {
        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> ApiException.notFound("拼车记录不存在"));
        if (ride.getOwnerId() == userId) {
            throw ApiException.validation("不能加入自己发起的拼车");
        }
        if (memberRepository.existsByRideIdAndUserId(rideId, userId)) {
            throw ApiException.validation("已加入该拼车，请勿重复加入");
        }
        assertActiveJoinedRideLimit(userId);

        // 高并发抢位重试：版本乐观锁下每轮只有一个事务能赢，失败者重读新版本再试。
        // READ_COMMITTED + 重试 8 次（实测 50 人并发抢 7 座可全部消化）；仍失败按最终状态精确归因返回 409。
        for (int attempt = 0; attempt < 8; attempt++) {
            int updated = rideRepository.joinAtomically(rideId, ride.getVersion(),
                    List.copyOf(RideStateMachine.JOINABLE), RideStatus.PUBLISHED, RideStatus.MATCHING);
            if (updated == 1) {
                memberRepository.save(new RideMember(rideId, userId, "CONFIRMED", OffsetDateTime.now()));
                // 只有原子抢位成功并已写入成员记录后才写事件：幂等回放、重复加入或抢位失败均不会通知车主。
                // 事件与 ride_member 同事务提交，由 notification 模块异步处理，避免业务写入依赖 MQ 可用性。
                emit(rideId, "RideMemberJoined", Map.of(
                        "rideId", rideId, "userId", userId, "ownerId", ride.getOwnerId(),
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
        // “我已打上车”同属实时拼供给侧；不能只在小程序入口拦截，直接调用导入接口也必须失败。
        featureAvailabilityService.requireRealtimeCarpoolEnabled();
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

    void validateStatusFields(CreateRideRequest req) {
        if (req.origin() == null || req.destination() == null || req.departTime() == null) {
            throw ApiException.validation("origin/destination/departTime 不能为空");
        }
        // 以业务时区而非请求自带 offset 比较“日期”：客户端可伪造 offset，不能借此跨过日期红线。
        LocalDate departDate = req.departTime().atZoneSameInstant(BUSINESS_ZONE).toLocalDate();
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        if (departDate.isBefore(today) || departDate.isAfter(today.plusMonths(1))) {
            throw ApiException.validation("预打车日期只能选择今天起一个月内");
        }
        if (req.waitingMinutes() != null
                && req.waitingMinutes() != 5 && req.waitingMinutes() != 10 && req.waitingMinutes() != 15) {
            throw ApiException.validation("waitingMinutes 只允许 5/10/15 或不传");
        }
    }

    /** 锁内计数，防止四个并发发布请求都先读到 2 条后一起写入。 */
    void assertPendingRideLimit(long ownerId) {
        if (rideRepository.countByOwnerIdAndStatusIn(ownerId, PENDING_COMPANION_STATUSES) >= MAX_PENDING_RIDES) {
            throw ApiException.validation(PENDING_RIDE_LIMIT_MESSAGE);
        }
    }

    /** 锁内计数，所有非终态成员 Ride 都算“同时加入”，直到行程真正关闭或异常结束。 */
    void assertActiveJoinedRideLimit(long userId) {
        if (memberRepository.countByUserIdAndRideStatusIn(userId, ACTIVE_MEMBER_RIDE_STATUSES)
                >= MAX_ACTIVE_JOINED_RIDES) {
            throw ApiException.validation("同时最多加入 3 个拼车，请完成或退出已有行程后再加入");
        }
    }

    private <T> T withUserLimitLock(long userId, Supplier<T> action) {
        String lockName = "pinhaoche:ride-limit:" + userId;
        if (!Integer.valueOf(1).equals(rideRepository.acquireUserLimitLock(lockName))) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "操作繁忙，请重试");
        }
        // GET_LOCK 是连接级而非事务级锁。不能在 action 返回时立即释放：那时本事务尚未
        // 提交，下一请求会读不到本次未提交的计数并绕过上限。提交/回滚完成前保持该锁。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                rideRepository.releaseUserLimitLock(lockName);
            }
        });
        return action.get();
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

    /**
     * 物理删除的 Ride 模块边界：先删 ride_member，再删 ride，避免 admin 跨模块直连 Ride repository。
     * 该方法由 AdminRideDeletionService 的外层事务协调；聊天、Outbox、幂等和历史审计均已在调用前清理，
     * 所以任一删除失败会使整个清理事务回滚，而不会留下半个订单。
     */
    public void deleteForAdmin(long rideId) {
        memberRepository.deleteByRideId(rideId);
        rideRepository.deleteById(rideId);
    }

    private static RideDto toDto(Ride r) {
        return new RideDto(r.getId(), r.getOwnerId(),
                new PlaceRef(r.getOriginType(), r.getOriginPlaceId(), r.getOriginText()),
                new PlaceRef(r.getDestinationType(), r.getDestinationPlaceId(), r.getDestinationText()),
                r.getDepartTime(), r.getMemberCount(), r.getCapacity(), r.getStatus(),
                r.getPlateNumber(), r.getProvider(), r.getRemainingDistance(),
                r.getDistanceUpdatedAt(), r.getWaitingMinutes(), r.getVersion(), r.getRemark());
    }

    /**
     * 跨模块只调用 user 的应用服务拿最小资料；Ride 不得直接读取 app_user 表或泄露 openid。
     * 成员记录与用户资料任一缺失时安全降级为不展示该联系人，客户端也无法据此创建会话。
     */
    private List<MemberContactDto> memberContacts(long rideId) {
        List<Long> memberIds = memberRepository.findByRideIdOrderByCreatedAtAsc(rideId).stream()
                .map(RideMember::getUserId).toList();
        return userContactService.findContactCards(memberIds).stream()
                .map(card -> new MemberContactDto(card.id(), card.nickname(), card.avatar())).toList();
    }

    // ---------- RFC-009 管理端只读能力（只追加，不动既有方法；AdminRideController 与 admin StatsController 使用） ----------

    /**
     * 管理端全量分页：status 为 null 表示不过滤（ALL）。
     * 排序由调用方 Pageable 携带（管理端约定 createdAt 倒序）；复用 toDto 保证与用户端字段完全一致。
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<RideDto> pageAll(RideStatus status,
                                                                 org.springframework.data.domain.Pageable pageable) {
        var result = status == null
                ? rideRepository.findAll(pageable)
                : rideRepository.findByStatus(status, pageable);
        return result.map(RideService::toDto);
    }

    /** 状态集合计数：ridesActive（PUBLISHED/MATCHING/BOOKED/IN_PROGRESS）与 ridesExpired（EXPIRED）共用。 */
    @Transactional(readOnly = true)
    public long countByStatusIn(List<RideStatus> statuses) {
        return rideRepository.countByStatusIn(statuses);
    }

    /** "今日新增"计数，时间边界由调用方统一用 UTC 计算。 */
    @Transactional(readOnly = true)
    public long countCreatedAfter(OffsetDateTime time) {
        return rideRepository.countByCreatedAtAfter(time);
    }

    /** 逐日统计：[from, to) 半开区间内创建的单数（from 含、to 不含，避免毫秒边界重复/遗漏）。 */
    @Transactional(readOnly = true)
    public long countCreatedBetween(OffsetDateTime from, OffsetDateTime to) {
        return rideRepository.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(from, to);
    }
}
