package com.pinhaoche.admin;

import com.pinhaoche.chat.ChatService;
import com.pinhaoche.common.api.ApiException;
import com.pinhaoche.infrastructure.outbox.IdempotencyRecordRepository;
import com.pinhaoche.infrastructure.outbox.OutboxEventRepository;
import com.pinhaoche.ride.RideService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 管理端不可恢复的订单清理协调器。
 *
 * 不能把这段逻辑塞进 RideService：它需要调用 Chat 应用服务清理会话，反向依赖会造成
 * RideService 与 admin/chat 的循环。这里仅协调各模块公开的应用服务和基础设施仓储，
 * 不直接读取 chat 或 ride 的表；一个外层事务确保任何清理步骤失败时全部回滚。
 */
@Service
public class AdminRideDeletionService {
    private final RideService rideService;
    private final ChatService chatService;
    private final OutboxEventRepository outboxRepository;
    private final IdempotencyRecordRepository idempotencyRepository;

    public AdminRideDeletionService(RideService rideService, ChatService chatService,
                                    OutboxEventRepository outboxRepository,
                                    IdempotencyRecordRepository idempotencyRepository) {
        this.rideService = rideService;
        this.chatService = chatService;
        this.outboxRepository = outboxRepository;
        this.idempotencyRepository = idempotencyRepository;
    }

    public record DeletionResult(long rideId) {
    }

    /** requested 为去重后的明确勾选订单数，deleted 只会在整批事务成功后等于 requested。 */
    public record BulkDeletionResult(int requested, int deleted) {
    }

    @Transactional
    public DeletionResult delete(long rideId) {
        validateRideId(rideId);
        // 先锁住订单行，确保 DELETE 404 不会被静默当作成功，也阻止并发写在清理后留下新事件。
        rideService.lockForAdminDeletion(rideId);
        deleteOne(rideId);
        return new DeletionResult(rideId);
    }

    @Transactional
    public BulkDeletionResult deleteAll(List<Long> rideIds) {
        List<Long> orderedIds = distinctPositiveIds(rideIds).stream().sorted().toList();
        // 按固定 ID 顺序先锁整批，避免多个管理员交叉批量删除时死锁；任何无效 ID 都不会开始清理。
        orderedIds.forEach(rideService::lockForAdminDeletion);
        orderedIds.forEach(this::deleteOne);
        return new BulkDeletionResult(orderedIds.size(), orderedIds.size());
    }

    private void deleteOne(long rideId) {
        // 聊天必须最先清理：chat_message 外键依赖 session；其中也会移除消息 Outbox。
        chatService.deleteRideDataForAdmin(rideId);
        // 已投递事件无法从 MQ 收回；此处删除未投递及持久化历史，防止后续扫描已不存在订单。
        outboxRepository.deleteByAggregateTypeAndAggregateId("ride", rideId);
        idempotencyRepository.deleteRideRecordsByResourceId(rideId);
        // 最后才让 Ride 模块删除成员与主记录，保证外键约束与跨模块边界同时成立。
        rideService.deleteForAdmin(rideId);
    }

    private static void validateRideId(long rideId) {
        if (rideId <= 0) {
            throw ApiException.validation("rideId 必须为正整数");
        }
    }

    private static Set<Long> distinctPositiveIds(List<Long> rideIds) {
        if (rideIds == null || rideIds.isEmpty()) {
            throw new IllegalArgumentException("rideIds 不能为空");
        }
        Set<Long> distinctIds = new LinkedHashSet<>(rideIds);
        if (distinctIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("rideIds 必须全部为正整数");
        }
        return distinctIds;
    }
}
