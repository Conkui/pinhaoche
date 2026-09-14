package com.pinhaoche.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pinhaoche.common.api.ApiException;
import com.pinhaoche.common.idempotency.IdempotencyService;
import com.pinhaoche.infrastructure.outbox.OutboxEventRepository;
import com.pinhaoche.ride.RideService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceDeleteTest {

    @Test
    void memberCanDeleteOnlyOwnMessageAndItsOutbox() {
        var sessions = mock(ChatSessionRepository.class);
        var messages = mock(ChatMessageRepository.class);
        var outbox = mock(OutboxEventRepository.class);
        ChatSession session = new ChatSession(9, 7, 42, OffsetDateTime.now());
        ChatMessage message = new ChatMessage(1, 7, "client", "TEXT", "hello", OffsetDateTime.now());
        setId(session, "id", 1L);
        setId(message, "id", 88L);
        when(sessions.findById(1L)).thenReturn(Optional.of(session));
        when(messages.findById(88L)).thenReturn(Optional.of(message));
        service(sessions, messages, outbox).deleteOwnMessage(1, 88, 7);

        verify(messages).delete(message);
        verify(outbox).deleteByAggregateTypeAndAggregateId("chat_message", 88L);
    }

    @Test
    void memberCannotDeletePeersMessage() {
        var sessions = mock(ChatSessionRepository.class);
        var messages = mock(ChatMessageRepository.class);
        var outbox = mock(OutboxEventRepository.class);
        ChatSession session = new ChatSession(9, 7, 42, OffsetDateTime.now());
        ChatMessage message = new ChatMessage(1, 42, "client", "TEXT", "hello", OffsetDateTime.now());
        setId(session, "id", 1L);
        setId(message, "id", 88L);
        when(sessions.findById(1L)).thenReturn(Optional.of(session));
        when(messages.findById(88L)).thenReturn(Optional.of(message));

        ApiException error = assertThrows(ApiException.class,
                () -> service(sessions, messages, outbox).deleteOwnMessage(1, 88, 7));

        assertEquals("FORBIDDEN", error.getCode().name());
        verify(messages, never()).delete(any());
        verify(outbox, never()).deleteByAggregateTypeAndAggregateId(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    private static ChatService service(ChatSessionRepository sessions, ChatMessageRepository messages,
                                       OutboxEventRepository outbox) {
        return new ChatService(sessions, messages, mock(OnlinePresence.class), mock(IdempotencyService.class),
                outbox, new ObjectMapper(), mock(StringRedisTemplate.class), mock(TransactionTemplate.class),
                mock(RideService.class));
    }

    private static void setId(Object target, String field, long value) {
        try {
            var declared = target.getClass().getDeclaredField(field);
            declared.setAccessible(true);
            declared.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
