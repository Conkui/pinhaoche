package com.pinhaoche.ride;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pinhaoche.common.api.ApiException;
import com.pinhaoche.common.idempotency.IdempotencyService;
import com.pinhaoche.infrastructure.outbox.OutboxEvent;
import com.pinhaoche.infrastructure.outbox.OutboxEventRepository;
import com.pinhaoche.feature.FeatureAvailabilityService;
import com.pinhaoche.place.PlaceService;
import com.pinhaoche.user.UserContactService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 加入事件的边界测试：仅真正抢位成功后才允许产生车主提醒事件。 */
class RideServiceJoinEventTest {

    private final RideRepository rides = mock(RideRepository.class);
    private final RideMemberRepository members = mock(RideMemberRepository.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RideService service = new RideService(rides, members, new RideStateMachine(),
            mock(PlaceService.class), mock(IdempotencyService.class), outbox, mapper,
            mock(UserContactService.class), mock(FeatureAvailabilityService.class));

    @Test
    void successfulJoinEmitsOneOwnerTargetedEventAfterMemberPersistence() throws Exception {
        Ride ride = ride(7L, 101L, 1, 3, RideStatus.PUBLISHED, 0L);
        when(rides.findById(7L)).thenReturn(Optional.of(ride));
        when(members.existsByRideIdAndUserId(7L, 202L)).thenReturn(false);
        when(rides.joinAtomically(anyLong(), anyLong(), anyList(), any(), any())).thenReturn(1);

        service.doJoin(7L, 202L);

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(members).save(any(RideMember.class));
        verify(outbox).save(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo("RideMemberJoined");
        JsonNode payload = mapper.readTree(event.getValue().getPayload());
        assertThat(payload.path("rideId").asLong()).isEqualTo(7L);
        assertThat(payload.path("ownerId").asLong()).isEqualTo(101L);
        assertThat(payload.path("userId").asLong()).isEqualTo(202L);
    }

    @Test
    void failedJoinDoesNotPersistMemberOrEmitReminderEvent() {
        Ride ride = ride(8L, 101L, 3, 3, RideStatus.MATCHING, 1L);
        when(rides.findById(8L)).thenReturn(Optional.of(ride));
        when(members.existsByRideIdAndUserId(8L, 202L)).thenReturn(false);
        when(rides.joinAtomically(anyLong(), anyLong(), anyList(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.doJoin(8L, 202L)).isInstanceOf(ApiException.class);

        verify(members, never()).save(any(RideMember.class));
        verify(outbox, never()).save(any(OutboxEvent.class));
    }

    @Test
    void ownerCanOnlyContactAMemberOfTheSameRide() {
        Ride ride = ride(9L, 101L, 1, 3, RideStatus.PUBLISHED, 0L);
        when(rides.findById(9L)).thenReturn(Optional.of(ride));
        when(members.existsByRideIdAndUserId(9L, 202L)).thenReturn(false);

        assertThatThrownBy(() -> service.assertChatPeerAllowed(9L, 101L, 202L))
                .isInstanceOf(ApiException.class)
                .hasMessage("只能联系已加入该订单的拼友");
    }

    @Test
    void publicViewerMayOnlyContactTheRideOwner() {
        Ride ride = ride(10L, 101L, 1, 3, RideStatus.PUBLISHED, 0L);
        when(rides.findById(10L)).thenReturn(Optional.of(ride));

        assertThatThrownBy(() -> service.assertChatPeerAllowed(10L, 202L, 303L))
                .isInstanceOf(ApiException.class)
                .hasMessage("非发布者只能联系该订单发布者");
    }

    private static Ride ride(long id, long ownerId, int memberCount, int capacity,
                             RideStatus status, long version) {
        Ride ride = new Ride();
        setId(ride, id);
        ride.setOwnerId(ownerId);
        ride.setOriginType("CUSTOM");
        ride.setOriginText("学校");
        ride.setDestinationType("CUSTOM");
        ride.setDestinationText("车站");
        ride.setDepartTime(OffsetDateTime.now().plusHours(1));
        ride.setMemberCount(memberCount);
        ride.setCapacity(capacity);
        ride.setStatus(status);
        ride.setVersion(version);
        return ride;
    }

    private static void setId(Ride ride, long id) {
        try {
            var idField = Ride.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(ride, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
