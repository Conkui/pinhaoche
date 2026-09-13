package com.pinhaoche.chat;

import com.pinhaoche.common.api.ApiException;
import com.pinhaoche.common.auth.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Chat HTTP 接口（openapi: /chat-sessions 系列含 RFC-001 列表接口）。
 * REST 只承担：建会话 / 断线补偿拉消息 / 标记已读；实时收发一律走 WebSocket。
 */
@RestController
@RequestMapping("/api/v1")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    public record CreateSessionRequest(long rideId, long peerUserId) {
    }

    @GetMapping("/chat-sessions")
    public List<ChatService.ChatSessionSummaryDto> listSessions(HttpServletRequest http) {
        return chatService.listSessions(userId(http));
    }

    @PostMapping("/chat-sessions")
    public ChatService.ChatSessionDto createSession(@RequestBody CreateSessionRequest request,
                                                    HttpServletRequest http) {
        return chatService.getOrCreateSession(userId(http), request.rideId(), request.peerUserId());
    }

    @GetMapping("/chat-sessions/{sessionId}/messages")
    public List<ChatService.MessageDto> messages(@PathVariable long sessionId,
                                                 @RequestParam(defaultValue = "0") long afterId,
                                                 HttpServletRequest http) {
        return chatService.messages(sessionId, userId(http), afterId);
    }

    @PostMapping("/chat-sessions/{sessionId}/read")
    public org.springframework.http.ResponseEntity<Void> markRead(@PathVariable long sessionId,
                                                                  HttpServletRequest http) {
        chatService.markRead(sessionId, userId(http));
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    private long userId(HttpServletRequest http) {
        Object v = http.getAttribute(AuthInterceptor.ATTR_USER_ID);
        if (v == null) {
            throw ApiException.unauthorized("未登录");
        }
        return (Long) v;
    }
}
