package com.pinhaoche.notification;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 通知防骚扰冷却（规范 §15）：
 * key notify:{sessionId}:{receiverId}，第一次产生未读 → 发微信提醒 → 5 分钟冷却；
 * 冷却期内只累加未读数，用户进入聊天阅读后未读清零，下一轮才允许再次通知。
 */
@Component
public class NotifyCooldownService {

    private static final Duration COOLDOWN = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;

    public NotifyCooldownService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @return true = 冷却期外，允许发送订阅消息；false = 冷却期内，只加未读。 */
    public boolean tryAcquire(long sessionId, long receiverId) {
        String key = "notify:%d:%d".formatted(sessionId, receiverId);
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", COOLDOWN));
        } catch (RuntimeException e) {
            // Redis 异常时宁可放弃本次通知也不轰炸用户（可用性取舍：漏通知 << 骚扰）
            return false;
        }
    }
}
