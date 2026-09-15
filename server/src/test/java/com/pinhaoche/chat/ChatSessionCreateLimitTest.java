package com.pinhaoche.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pinhaoche.common.api.ApiException;
import com.pinhaoche.common.idempotency.IdempotencyService;
import com.pinhaoche.common.security.RateLimitService;
import com.pinhaoche.infrastructure.outbox.OutboxEventRepository;
import com.pinhaoche.ride.RideService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 新建会话限流：已存在会话永远优先复用，只有首次创建才进入每日桶。 */
class ChatSessionCreateLimitTest {

    @Test
    void existingSessionIsReusedWithoutConsumingDailyQuota() {
        var sessions = mock(ChatSessionRepository.class);
        var rateLimit = mock(RateLimitService.class);
        ChatSession session = new ChatSession(9, 7, 42, OffsetDateTime.now());
        setId(session, 1L);
        when(sessions.findByRideIdAndUserLowIdAndUserHighId(9, 7, 42)).thenReturn(Optional.of(session));

        service(sessions, rateLimit).getOrCreateSession(7, 9, 42);

        verify(rateLimit, never()).tryAcquire(any(), anyInt(), any());
    }

    @Test
    void eleventhNewSessionIsRejectedByDailyQuota() {
        var sessions = mock(ChatSessionRepository.class);
        var rateLimit = mock(RateLimitService.class);
        when(sessions.findByRideIdAndUserLowIdAndUserHighId(9, 7, 42)).thenReturn(Optional.empty());
        when(rateLimit.tryAcquire(any(), eq(10), any(Duration.class))).thenReturn(false);

        ApiException error = assertThrows(ApiException.class,
                () -> service(sessions, rateLimit).getOrCreateSession(7, 9, 42));

        assertThat(error.getCode().name()).isEqualTo("RATE_LIMITED");
        verify(sessions, never()).saveAndFlush(any());
    }

    @Test
    void dailyScopesAreSeparatedByNaturalDate() {
        assertThat(ChatService.sessionCreateBucket(LocalDate.of(2026, 9, 15), 7))
                .isEqualTo("rl:chat-session:2026-09-15:7");
        assertThat(ChatService.sessionCreateBucket(LocalDate.of(2026, 9, 16), 7))
                .isEqualTo("rl:chat-session:2026-09-16:7");
        assertThat(ChatService.sessionCreateBucket(LocalDate.of(2026, 9, 15), 42))
                .isNotEqualTo(ChatService.sessionCreateBucket(LocalDate.of(2026, 9, 15), 7));
    }

    @Test
    void duplicateCreationRaceReleasesQuotaBeforeReusingSession() {
        var sessions = mock(ChatSessionRepository.class);
        var rateLimit = mock(RateLimitService.class);
        var tx = mock(TransactionTemplate.class);
        ChatSession session = new ChatSession(9, 7, 42, OffsetDateTime.now());
        setId(session, 1L);
        when(sessions.findByRideIdAndUserLowIdAndUserHighId(9, 7, 42))
                .thenReturn(Optional.empty(), Optional.of(session));
        when(rateLimit.tryAcquire(any(), eq(10), any(Duration.class))).thenReturn(true);
        when(tx.execute(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));

        service(sessions, rateLimit, tx).getOrCreateSession(7, 9, 42);

        verify(rateLimit).release(ChatService.sessionCreateBucket(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")), 7));
    }

    @Test
    void persistenceFailureReleasesReservedQuota() {
        var sessions = mock(ChatSessionRepository.class);
        var rateLimit = mock(RateLimitService.class);
        var tx = mock(TransactionTemplate.class);
        when(sessions.findByRideIdAndUserLowIdAndUserHighId(9, 7, 42)).thenReturn(Optional.empty());
        when(rateLimit.tryAcquire(any(), eq(10), any(Duration.class))).thenReturn(true);
        when(tx.execute(any())).thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class, () -> service(sessions, rateLimit, tx).getOrCreateSession(7, 9, 42));

        verify(rateLimit).release(ChatService.sessionCreateBucket(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")), 7));
    }

    private static ChatService service(ChatSessionRepository sessions, RateLimitService rateLimit) {
        return service(sessions, rateLimit, mock(TransactionTemplate.class));
    }

    private static ChatService service(ChatSessionRepository sessions, RateLimitService rateLimit, TransactionTemplate tx) {
        var rides = mock(RideService.class);
        return new ChatService(sessions, mock(ChatMessageRepository.class), mock(OnlinePresence.class),
                mock(IdempotencyService.class), mock(OutboxEventRepository.class), new ObjectMapper(),
                mock(StringRedisTemplate.class), tx, rides, rateLimit);
    }

    private static void setId(ChatSession target, long value) {
        try {
            var field = ChatSession.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
