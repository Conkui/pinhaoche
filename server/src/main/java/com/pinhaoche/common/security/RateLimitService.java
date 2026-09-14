package com.pinhaoche.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 接口限流服务（RFC-010）：Redis 固定窗口计数。
 *
 * 为什么用固定窗口而不是滑动窗口/令牌桶：
 * - Redis 侧只需 INCR + （首次）EXPIRE 两条命令，O(1) 且实现简单，符合 V1 "够用即可" 的取舍；
 * - 窗口边界允许 2 倍突刺（上一个窗口末尾 + 本窗口开头），对拼车场景的低频正常用户完全无感，
 *   对防刷/防撞库而言 20 次/分钟 × 2 倍突刺仍在可接受范围；
 * - 键里带窗口序号（如 rl:ip:2892345:1.2.3.4），天然不会跨窗口复用，TTL 只是为了清理旧键。
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    /** 固定窗口长度：1 分钟（RFC-010 的两个限额都按"次/分钟"定义）。 */
    public static final long WINDOW_MILLIS = 60_000L;
    /**
     * 键 TTL 120 秒 = 2 个窗口：首次访问该窗口的请求设置一次即可。
     * 窗口序号单调递增，旧窗口键不可能被复用，TTL 仅用于 Redis 自动清理。
     */
    public static final long KEY_TTL_SECONDS = 120L;

    private final StringRedisTemplate redis;

    public RateLimitService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 窗口计数 +1，并判断本次请求是否放行。
     *
     * @param bucketKey 完整桶键（含窗口序号），由 {@link #windowKey} 生成
     * @param limit     窗口内允许的最大请求数
     * @return true=放行；false=超限（调用方抛 RATE_LIMITED）
     */
    public boolean tryAcquire(String bucketKey, int limit) {
        try {
            Long count = redis.opsForValue().increment(bucketKey);
            if (count == null) {
                // 理论上不会出现（INCR 必返回数值），防御性放行，避免误伤正常用户
                log.warn("限流 INCR 返回空，放行 key={}", bucketKey);
                return true;
            }
            if (count == 1L) {
                // 只有窗口内第一个请求设置 TTL：若每次 INCR 都续期，活跃桶将永不过期，垃圾键越积越多
                redis.expire(bucketKey, Duration.ofSeconds(KEY_TTL_SECONDS));
            }
            return isAllowed(count, limit);
        } catch (RuntimeException e) {
            // fail-open（可用性优先）：限流是自我保护措施而非业务功能。若 Redis 故障时选择
            // fail-closed（全部拒绝），一次缓存故障会被放大成全站不可用；放行后 DB 唯一键、
            // 幂等、鉴权、状态机等兜底仍然生效，短时间刷请求的代价可接受（RFC-010 明确约定）。
            // 降级写法与 IdempotencyService 保持一致：捕获 RuntimeException、log.warn、放行。
            log.warn("Redis 不可用，限流放行（fail-open）key={}: {}", bucketKey, e.getMessage());
            return true;
        }
    }

    /**
     * 当前分钟窗口的桶键：{prefix}:{窗口序号}:{identity}，如 rl:ip:2892345:1.2.3.4。
     * 纯静态函数（窗口序号 = epochMilli / 60000），便于脱离 Spring/Redis 做单元测试。
     */
    public static String windowKey(String prefix, String identity, long epochMilli) {
        return "%s:%d:%s".formatted(prefix, epochMilli / WINDOW_MILLIS, identity);
    }

    /**
     * 限额判定：INCR 后的计数 ≤ limit 才放行（第 limit 次仍放行，第 limit+1 次拒绝）。
     * 纯静态函数，便于单元测试边界值。
     */
    public static boolean isAllowed(long countInWindow, int limit) {
        return countInWindow <= limit;
    }
}
