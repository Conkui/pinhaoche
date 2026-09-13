package com.pinhaoche.chat;

/**
 * 在线状态接口（docs/ARCHITECTURE.md 最低接口）。
 * "在线"语义 = 用户 WebSocket 已连接且正停留在 sessionId 对应聊天页。
 * 实现（Redis）：key chat:active:{sessionId}:{userId}，由 ENTER_SESSION/EXIT_SESSION 维护，TTL 90s 兜底。
 */
public interface OnlinePresence {

    void enterSession(long userId, long sessionId);

    void exitSession(long userId, long sessionId);

    boolean isInSession(long userId, long sessionId);
}
