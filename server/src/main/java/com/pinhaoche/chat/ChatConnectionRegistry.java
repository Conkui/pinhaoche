package com.pinhaoche.chat;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 连接注册表（进程内）。V1 单实例部署足够；扩多实例时替换为
 * Redis pub/sub 或专用路由层（规范 §13：Redis 承担连接路由）。
 * 职责：userId → 其活跃连接集合；推送时选择"正处于该聊天页"的连接优先。
 */
@Component
public class ChatConnectionRegistry {

    private final Map<Long, Map<Long, WebSocketSession>> userSessions = new ConcurrentHashMap<>();

    public void register(long userId, long sessionId, WebSocketSession session) {
        userSessions.computeIfAbsent(userId, k -> new ConcurrentHashMap<>()).put(sessionId, session);
    }

    public void unregister(long userId, long sessionId) {
        var sessions = userSessions.get(userId);
        if (sessions != null) {
            sessions.remove(sessionId);
            if (sessions.isEmpty()) {
                userSessions.remove(userId, sessions);
            }
        }
    }

    public boolean push(long userId, long sessionId, String json) {
        WebSocketSession session = userSessions.getOrDefault(userId, Map.of()).get(sessionId);
        if (session == null || !session.isOpen()) {
            return false;
        }
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
            return true;
        } catch (IOException | IllegalStateException e) {
            return false;
        }
    }
}
