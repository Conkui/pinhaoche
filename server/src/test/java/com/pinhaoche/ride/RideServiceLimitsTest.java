package com.pinhaoche.ride;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pinhaoche.common.api.ApiException;
import com.pinhaoche.common.idempotency.IdempotencyService;
import com.pinhaoche.feature.FeatureAvailabilityService;
import com.pinhaoche.infrastructure.outbox.OutboxEventRepository;
import com.pinhaoche.place.PlaceService;
import com.pinhaoche.user.UserContactService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 日期与每用户上限的领域测试；并发串行化由生产路径的 MySQL 用户命名锁负责。 */
class RideServiceLimitsTest {

    private final RideRepository rides = mock(RideRepository.class);
    private final RideMemberRepository members = mock(RideMemberRepository.class);
    private final RideService service = new RideService(rides, members, new RideStateMachine(),
            mock(PlaceService.class), mock(IdempotencyService.class), mock(OutboxEventRepository.class),
            new ObjectMapper(), mock(UserContactService.class), mock(FeatureAvailabilityService.class));

    @Test
    void prebookDateUsesShanghaiBusinessDateInsteadOfClientOffset() {
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        LocalDate today = LocalDate.now(shanghai);
        // 同一个 instant 带 UTC offset 时，其本地日期可能是昨天；仍须按上海日期判断。
        var todayInShanghai = request(today.atStartOfDay(shanghai).plusMinutes(30).toOffsetDateTime().withOffsetSameInstant(java.time.ZoneOffset.UTC));
        assertThatCode(() -> service.validateStatusFields(todayInShanghai)).doesNotThrowAnyException();

        var yesterdayInShanghai = request(today.minusDays(1).atStartOfDay(shanghai).toOffsetDateTime());
        assertThatThrownBy(() -> service.validateStatusFields(yesterdayInShanghai))
                .isInstanceOf(ApiException.class)
                .hasMessage("预打车日期只能选择今天起一个月内");
    }

    @Test
    void prebookDateRejectsMoreThanOneCalendarMonthAhead() {
        var beyondOneMonth = request(LocalDate.now(ZoneId.of("Asia/Shanghai")).plusMonths(1).plusDays(1)
                .atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime());
        assertThatThrownBy(() -> service.validateStatusFields(beyondOneMonth))
                .isInstanceOf(ApiException.class)
                .hasMessage("预打车日期只能选择今天起一个月内");
    }

    @Test
    void pendingRideLimitCountsOnlyPublishedAndMatching() {
        when(rides.countByOwnerIdAndStatusIn(eq(7L), anyList())).thenReturn(3L);

        assertThatThrownBy(() -> service.assertPendingRideLimit(7L))
                .isInstanceOf(ApiException.class)
                .hasMessage("当前委托较多，请完成或下架后再发布");
        verify(rides).countByOwnerIdAndStatusIn(7L, List.of(RideStatus.PUBLISHED, RideStatus.MATCHING));
    }

    @Test
    void joinedRideLimitCountsAllNonTerminalStatesIncludingArrived() {
        when(members.countByUserIdAndRideStatusIn(eq(9L), anyList())).thenReturn(3L);

        assertThatThrownBy(() -> service.assertActiveJoinedRideLimit(9L))
                .isInstanceOf(ApiException.class)
                .hasMessage("同时最多加入 3 个拼车，请完成或退出已有行程后再加入");
        verify(members).countByUserIdAndRideStatusIn(9L, List.of(
                RideStatus.PUBLISHED, RideStatus.MATCHING, RideStatus.BOOKED,
                RideStatus.IN_PROGRESS, RideStatus.ARRIVED));
    }

    private static RideService.CreateRideRequest request(OffsetDateTime departTime) {
        var place = new RideService.PlaceRef("CUSTOM", null, "学校");
        return new RideService.CreateRideRequest(place, place, departTime, 1, 2, null, null);
    }
}
