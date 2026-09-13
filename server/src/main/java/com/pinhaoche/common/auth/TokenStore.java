package com.pinhaoche.common.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 登录 token 存取（Redis）。
 * 为什么不用 JWT：V1 无注销/多端管理需求且要保证"服务端以 token 身份覆盖任何客户端用户字段"
 * （docs/WEBSOCKET_PROTOCOL.md），集中式 token 便于服务端随时吊销，代价小。
 */
@Component
public class TokenStore {

    private final StringRedisTemplate redis;
    private final long ttlHours;

    public TokenStore(StringRedisTemplate redis, @Value("${app.token-ttl-hours:72}") long ttlHours) {
        this.redis = redis;
        this.ttlHours = ttlHours;
    }

    public String issue(long userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(key(token), String.valueOf(userId), Duration.ofHours(ttlHours));
        return token;
    }

    /** 解析 token → userId；无效返回 null，由调用方决定 401。 */
    public Long resolve(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String v = redis.opsForValue().get(key(token));
        return v == null ? null : Long.parseLong(v);
    }

    private String key(String token) {
        return "auth:token:" + token;
    }
}
