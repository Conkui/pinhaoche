package com.pinhaoche.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;

/**
 * 聊天 WebSocket 处理器（/ws/v1/chat，docs/WEBSOCKET_PROTOCOL.md）。
 * 帧类型：SEND_MESSAGE / ENTER_SESSION / EXIT_SESSION / PING（客户端），
 * ACK / MESSAGE / ERROR / PONG（服务端）。
 * 关键顺序：服务端"落库 → 提交 → ACK 发送者 → 推送接收者"，禁止先推送后保存（规范 §11）。
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    public static final String ATTR_USER_ID = "phc.userId";
    public static final String ATTR_ACTIVE_SESSION = "phc.activeSession";

    private final ChatService chatService;
    private final ChatConnectionRegistry registry;
    private final OnlinePresence presence;
    private final ObjectMapper objectMapper;

    public ChatWebSocketHandler(ChatService chatService, ChatConnectionRegistry registry,
                                OnlinePresence presence, ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.registry = registry;
        this.presence = presence;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get(ATTR_USER_ID);
        log.info("WS 连接建立 userId={}", userId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        Long userId = (Long) session.getAttributes().get(ATTR_USER_ID);
        if (userId == null) {
            send(session, Map.of("type", "ERROR", "code", "UNAUTHORIZED", "message", "连接未鉴权"));
            return;
        }
        JsonNode frame;
        try {
            frame = objectMapper.readTree(message.getPayload());
        } catch (Exception e) {
            send(session, Map.of("type", "ERROR", "code", "VALIDATION_ERROR", "message", "非法 JSON"));
            return;
        }
        String type = frame.path("type").asText("");
        switch (type) {
            case "PING" -> send(session, Map.of("type", "PONG"));
            case "ENTER_SESSION" -> {
                long sessionId = frame.path("sessionId").asLong();
                chatService.mustGetSession(sessionId);
                chatService.assertMemberForWs(sessionId, userId);
                session.getAttributes().put(ATTR_ACTIVE_SESSION, sessionId);
                registry.register(userId, sessionId, session);
                presence.enterSession(userId, sessionId);
                //进入聊天页 = 阅读完成，未读清零（规范 §15），下一轮才允许再次微信提醒
                chatService.markRead(sessionId, userId);
            }
            case "EXIT_SESSION" -> {
                long sessionId = frame.path("sessionId").asLong();
                session.getAttributes().remove(ATTR_ACTIVE_SESSION);
                registry.unregister(userId, sessionId);
                presence.exitSession(userId, sessionId);
            }
            case "SEND_MESSAGE" -> handleSend(session, userId, frame);
            default -> send(session, Map.of("type", "ERROR", "code", "VALIDATION_ERROR",
                    "message", "未知消息类型: " + type));
        }
    }

    private void handleSend(WebSocketSession session, long userId, JsonNode frame) throws IOException {
        String clientMsgId = frame.path("clientMsgId").asText(null);
        long sessionId = frame.path("sessionId").asLong();
        String content = frame.path("content").asText(null);
        try {
            // 事务在 service 方法边界提交；返回后才能 ACK（协议顺序保证）
            ChatService.MessageDto saved = chatService.sendMessage(sessionId, userId, clientMsgId, content);
            send(session, Map.of("type", "ACK", "clientMsgId", clientMsgId,
                    "messageId", saved.id(), "persistedAt", saved.createdAt().toString()));

            ChatSession chatSession = chatService.mustGetSession(sessionId);
            long receiverId = chatSession.peerOf(userId);
            boolean receiverOnPage = presence.isInSession(receiverId, sessionId);
            if (receiverOnPage) {
                // 接收者在该聊天页：直接 WebSocket 推送，不发订阅消息（规范 §14）
                boolean pushed = registry.push(receiverId, sessionId, objectMapper.writeValueAsString(
                        Map.of("type", "MESSAGE", "message", saved)));
                if (!pushed) {
                    chatService.incrementUnread(sessionId, receiverId);
                }
            } else {
                // 接收者不在页面：未读累加；订阅消息由 MessageReceived 事件消费端（冷却后）发送
                chatService.incrementUnread(sessionId, receiverId);
            }
        } catch (Exception e) {
            send(session, Map.of("type", "ERROR", "code", "VALIDATION_ERROR",
                    "clientMsgId", clientMsgId == null ? "" : clientMsgId,
                    "message", e.getMessage() == null ? "发送失败" : e.getMessage()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = (Long) session.getAttributes().get(ATTR_USER_ID);
        Object active = session.getAttributes().get(ATTR_ACTIVE_SESSION);
        if (userId != null && active instanceof Long sessionId) {
            registry.unregister(userId, sessionId);
            presence.exitSession(userId, sessionId);
        }
        log.info("WS 连接关闭 userId={} status={}", userId, status);
    }

    private void send(WebSocketSession session, Map<String, Object> payload) throws IOException {
        synchronized (session) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        }
    }
}
