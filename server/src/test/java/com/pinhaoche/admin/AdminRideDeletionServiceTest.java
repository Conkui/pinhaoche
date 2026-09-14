package com.pinhaoche.admin;

import com.pinhaoche.chat.ChatService;
import com.pinhaoche.infrastructure.outbox.IdempotencyRecordRepository;
import com.pinhaoche.infrastructure.outbox.OutboxEventRepository;
import com.pinhaoche.ride.RideService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 协调器单测重点保护删除顺序：聊天先于订单，订单事件/幂等先于 Ride 主记录。
 * 真正的事务回滚由 @Transactional 和数据库集成验证保证；此处确保异常不会被吞掉继续半删。
 */
class AdminRideDeletionServiceTest {

    private final RideService rides = mock(RideService.class);
    private final ChatService chats = mock(ChatService.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final IdempotencyRecordRepository idempotency = mock(IdempotencyRecordRepository.class);
    private final AdminRideDeletionService service = new AdminRideDeletionService(rides, chats, outbox, idempotency);

    @Test
    void singleDeletionCleansDependentDataBeforeRide() {
        var result = service.delete(12L);

        assertThat(result.rideId()).isEqualTo(12L);
        InOrder order = inOrder(rides, chats, outbox, idempotency);
        order.verify(rides).lockForAdminDeletion(12L);
        order.verify(chats).deleteRideDataForAdmin(12L);
        order.verify(outbox).deleteByAggregateTypeAndAggregateId("ride", 12L);
        order.verify(idempotency).deleteRideRecordsByResourceId(12L);
        order.verify(rides).deleteForAdmin(12L);
    }

    @Test
    void batchDeduplicatesAndValidatesEverythingBeforeDeletingAnything() {
        var result = service.deleteAll(List.of(12L, 34L, 12L));

        assertThat(result.requested()).isEqualTo(2);
        assertThat(result.deleted()).isEqualTo(2);
        InOrder order = inOrder(rides, chats);
        order.verify(rides).lockForAdminDeletion(12L);
        order.verify(rides).lockForAdminDeletion(34L);
        order.verify(chats).deleteRideDataForAdmin(12L);
        order.verify(chats).deleteRideDataForAdmin(34L);
    }

    @Test
    void dependentCleanupFailureIsPropagatedWithoutDeletingRide() {
        doThrow(new IllegalStateException("chat cleanup failed"))
                .when(chats).deleteRideDataForAdmin(12L);

        assertThatThrownBy(() -> service.delete(12L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("chat cleanup failed");

        verify(rides, never()).deleteForAdmin(12L);
        verify(outbox, never()).deleteByAggregateTypeAndAggregateId("ride", 12L);
        verify(idempotency, never()).deleteRideRecordsByResourceId(12L);
    }

    @Test
    void invalidBatchFailsBeforeAnyCrossModuleCall() {
        assertThatThrownBy(() -> service.deleteAll(List.of(12L, 0L)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(rides, never()).lockForAdminDeletion(12L);
        verify(chats, never()).deleteRideDataForAdmin(12L);
    }
}
