package com.pinhaoche.common.idempotency;

import com.pinhaoche.common.api.ApiException;
import com.pinhaoche.common.api.ErrorCode;
import com.pinhaoche.infrastructure.outbox.IdempotencyRecord;
import com.pinhaoche.infrastructure.outbox.IdempotencyRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * HTTP 写操作幂等（规范 §21）：Redis SETNX 挡住瞬时重复请求，
 * idempotency_record 表的唯一键 (actor_id, idempotency_key) 是重启/过期后的最终保障。
 *
 * 流程：
 * 1) SETNX 成功 → 执行业务 → 业务结果 resourceId 落库；落库冲突说明并发重复 → 读已有记录返回同资源。
 * 2) SETNX 失败 → 读记录：requestHash 相同 → 返回已保存资源；不同 → 同 key 不同请求，IDEMPOTENCY_CONFLICT。
 * 3) Redis 不可用时直接降级走数据库唯一键（可用性优先于性能）。
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final Duration KEY_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final IdempotencyRecordRepository repository;

    public IdempotencyService(StringRedisTemplate redis, IdempotencyRecordRepository repository) {
        this.redis = redis;
        this.repository = repository;
    }

    /**
     * @param actorId    操作者（认证后的 userId，不接受客户端自报身份）
     * @param resourceId 结果资源提取函数，用于重复请求时回放
     */
    public <T> T execute(long actorId, String idempotencyKey, String operation,
                         Object request, Function<Long, T> replay, Supplier<T> action) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw ApiException.validation("缺少 Idempotency-Key 请求头");
        }
        String hash = sha256(request == null ? "" : String.valueOf(request));
        String redisKey = "idem:%d:%s".formatted(actorId, idempotencyKey);
        boolean first;
        try {
            first = Boolean.TRUE.equals(redis.opsForValue()
                    .setIfAbsent(redisKey, hash, KEY_TTL));
        } catch (RuntimeException e) {
            log.warn("Redis 不可用，幂等降级为仅数据库唯一键: {}", e.getMessage());
            first = true;
        }

        if (!first) {
            IdempotencyRecord existing = mustFind(actorId, idempotencyKey);
            if (!existing.getRequestHash().equals(hash)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "相同 Idempotency-Key 但请求内容不同");
            }
            return replay.apply(existing.getResourceId());
        }

        // 业务失败时补偿删除 Redis 键：否则同 key 的重试会命中 SETNX 失败且无 DB 记录，
        // 被误判为 IDEMPOTENCY_CONFLICT，客户端永远无法用原幂等键重试（集成验收发现）。
        T result;
        try {
            result = action.get();
        } catch (RuntimeException e) {
            try {
                redis.delete(redisKey);
            } catch (RuntimeException ignored) {
            }
            throw e;
        }
        long resourceId = result instanceof Resource r ? r.id() : 0L;
        try {
            repository.save(new IdempotencyRecord(actorId, idempotencyKey, operation, hash,
                    resourceId > 0 ? resourceId : null,
                    OffsetDateTime.now(), OffsetDateTime.now().plus(KEY_TTL.toHours(), ChronoUnit.HOURS)));
        } catch (DataIntegrityViolationException e) {
            // 并发重复：业务可能已执行两次？不会——唯一键在事务提交时拦截，
            // 先到者已保存 resourceId；后者事务回滚，返回先到者的资源。
            IdempotencyRecord winner = mustFind(actorId, idempotencyKey);
            if (!winner.getRequestHash().equals(hash)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "相同 Idempotency-Key 但请求内容不同");
            }
            return replay.apply(winner.getResourceId());
        }
        return result;
    }

    private IdempotencyRecord mustFind(long actorId, String key) {
        return repository.findByActorIdAndIdempotencyKey(actorId, key)
                .orElseThrow(() -> new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "幂等记录不存在"));
    }

    private String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append("%02x".formatted(b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 业务结果实现的标记接口：提供可回放的资源 id。 */
    public interface Resource {
        long id();
    }
}
