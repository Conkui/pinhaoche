package com.pinhaoche.ride;

import com.pinhaoche.common.api.ApiException;
import com.pinhaoche.common.api.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 状态机纯逻辑测试：合法迁移表必须与 docs/STATE_MACHINE.md 一致。 */
class RideStateMachineTest {

    private final RideStateMachine machine = new RideStateMachine();

    @Test
    void mainPathIsAllowed() {
        assertThat(machine.canTransit(RideStatus.DRAFT, RideStatus.PUBLISHED)).isTrue();
        assertThat(machine.canTransit(RideStatus.PUBLISHED, RideStatus.MATCHING)).isTrue();
        assertThat(machine.canTransit(RideStatus.PUBLISHED, RideStatus.BOOKED)).isTrue();
        assertThat(machine.canTransit(RideStatus.MATCHING, RideStatus.BOOKED)).isTrue();
        assertThat(machine.canTransit(RideStatus.BOOKED, RideStatus.IN_PROGRESS)).isTrue();
        assertThat(machine.canTransit(RideStatus.IN_PROGRESS, RideStatus.ARRIVED)).isTrue();
        assertThat(machine.canTransit(RideStatus.ARRIVED, RideStatus.CLOSED)).isTrue();
    }

    @Test
    void cancelAndExpireFollowSpec() {
        assertThat(machine.canTransit(RideStatus.PUBLISHED, RideStatus.CANCELLED)).isTrue();
        assertThat(machine.canTransit(RideStatus.MATCHING, RideStatus.CANCELLED)).isTrue();
        assertThat(machine.canTransit(RideStatus.BOOKED, RideStatus.CANCELLED)).isTrue();
        assertThat(machine.canTransit(RideStatus.IN_PROGRESS, RideStatus.CANCELLED)).isTrue();
        assertThat(machine.canTransit(RideStatus.PUBLISHED, RideStatus.EXPIRED)).isTrue();
        assertThat(machine.canTransit(RideStatus.MATCHING, RideStatus.EXPIRED)).isTrue();
    }

    @Test
    void illegalJumpsAreRejected() {
        // 不能跳级、终态不可恢复、不能逆向
        assertThat(machine.canTransit(RideStatus.PUBLISHED, RideStatus.IN_PROGRESS)).isFalse();
        assertThat(machine.canTransit(RideStatus.CLOSED, RideStatus.PUBLISHED)).isFalse();
        assertThat(machine.canTransit(RideStatus.CANCELLED, RideStatus.PUBLISHED)).isFalse();
        assertThat(machine.canTransit(RideStatus.EXPIRED, RideStatus.PUBLISHED)).isFalse();
        assertThat(machine.canTransit(RideStatus.ARRIVED, RideStatus.BOOKED)).isFalse();
    }

    @Test
    void assertTransitThrowsIllegalTransition() {
        assertThatThrownBy(() -> machine.assertTransit(RideStatus.CLOSED, RideStatus.PUBLISHED))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ILLEGAL_TRANSITION));
    }

    @Test
    void joinableStatusesMatchSpec() {
        assertThat(RideStateMachine.JOINABLE).containsExactlyInAnyOrder(
                RideStatus.PUBLISHED, RideStatus.MATCHING, RideStatus.BOOKED);
    }
}
