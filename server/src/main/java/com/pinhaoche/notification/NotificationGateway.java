package com.pinhaoche.notification;

/**
 * 微信订阅消息网关接口（接口优先约定）。
 * 为什么必须接口化：微信订阅消息需要模板 id / 一次性授权，且测试环境不可真实下发；
 * 应用层只依赖本接口，生产实现可独立替换/测试（docs/ARCHITECTURE.md）。
 * 不得假设服务端可以无限主动发送微信通知（规范 §14）——调用方必须先过冷却。
 */
public interface NotificationGateway {

    NotificationResult send(NotificationCommand command);

    record NotificationCommand(long receiverId, long sessionId, long messageId, String preview) {
    }

    record NotificationResult(boolean accepted, String reason) {
    }
}
