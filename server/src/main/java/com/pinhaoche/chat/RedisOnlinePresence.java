package com.pinhaoche.chat;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 在线状态的 Redis 实现。为什么用 TTL：用户直接杀进程时不会发 EXIT_SESSION，
 * 90 秒 TTL 保证"困在在线状态"自愈，宁可漏发订阅消息也不长期误判在线（漏发代价小）。
 */
@Component
public class RedisOnlinePresence implements OnlinePresence {

    private static final Duration TTL = Duration.ofSeconds(90);

    private final StringRedisTemplate redis;

    public RedisOnlinePresence(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String key(long userId, long sessionId) {
        return "chat:active:%d:%d".formatted(sessionId, userId);
    }

    @Override
    public void enterSession(long userId, long sessionId) {
        redis.opsForValue().set(key(userId, sessionId), "1", TTL);
    }

    @Override
    public void exitSession(long userId, long sessionId) {
        redis.delete(key(userId, sessionId));
    }

    @Override
    public boolean isInSession(long userId, long sessionId) {
        Boolean has = redis.hasKey(key(userId, sessionId));
        return Boolean.TRUE.equals(has);
    }
}
