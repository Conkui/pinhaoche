package com.pinhaoche.common.security;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 限流窗口纯逻辑测试（RFC-010）：窗口键生成与放行判定被抽成纯静态方法后，
 * 无需 Spring 上下文/真 Redis 即可验证边界——Redis 只负责"计数"本身，计数语义在 tryAcquire 内。
 */
class RateLimitWindowTest {

    private static final long MINUTE = RateLimitService.WINDOW_MILLIS;

    /** 2026-09-14T00:00:00Z = 1789344000000ms，是一个确定的分钟窗口起点；固定时间戳避免测试依赖"当前时间"。 */
    private static final long T0 = Instant.parse("2026-09-14T00:00:00Z").toEpochMilli();

    @Test
    void windowKeyIsPrefixWindowIdentity() {
        // 1789344000000 / 60000 = 29822400；键结构 {prefix}:{窗口号}:{身份}
        assertThat(T0).isEqualTo(1789344000000L);
        assertThat(RateLimitService.windowKey("rl:ip", "1.2.3.4", T0))
                .isEqualTo("rl:ip:29822400:1.2.3.4");
        assertThat(RateLimitService.windowKey("rl:u", "42", T0))
                .isEqualTo("rl:u:29822400:42");
    }

    @Test
    void sameMinuteFallsIntoSameWindow() {
        // 同一分钟内（含最后一毫秒）必须命中同一个桶，计数才正确
        assertThat(RateLimitService.windowKey("rl:ip", "1.2.3.4", T0 + MINUTE - 1))
                .isEqualTo(RateLimitService.windowKey("rl:ip", "1.2.3.4", T0));
    }

    @Test
    void nextMinuteStartsNewBucket() {
        // 新窗口键不同：旧桶随 TTL（120s）自动过期，窗口切换不依赖任何清理任务
        assertThat(RateLimitService.windowKey("rl:ip", "1.2.3.4", T0 + MINUTE))
                .isNotEqualTo(RateLimitService.windowKey("rl:ip", "1.2.3.4", T0));
    }

    @Test
    void differentIdentityNeverSharesBucket() {
        // 不同用户/IP 不能共用桶，否则一人超限全员超限
        assertThat(RateLimitService.windowKey("rl:u", "1", T0))
                .isNotEqualTo(RateLimitService.windowKey("rl:u", "2", T0));
        assertThat(RateLimitService.windowKey("rl:u", "1", T0))
                .isNotEqualTo(RateLimitService.windowKey("rl:ip", "1", T0));
    }

    @Test
    void isAllowedBoundaryIsInclusiveLimit() {
        // 第 limit 次放行、第 limit+1 次拒绝（INCR 后的计数与阈值比较，含边界）
        assertThat(RateLimitService.isAllowed(1, 20)).isTrue();
        assertThat(RateLimitService.isAllowed(20, 20)).isTrue();
        assertThat(RateLimitService.isAllowed(21, 20)).isFalse();
        assertThat(RateLimitService.isAllowed(120, 120)).isTrue();
        assertThat(RateLimitService.isAllowed(121, 120)).isFalse();
        assertThat(RateLimitService.isAllowed(0, 120)).isTrue();
    }

    @Test
    void configurableWindowRejectsNonPositiveTtlBeforeTouchingRedis() {
        // 自然日桶会传入“距下一次本地零点”的 TTL；零/负值会让 Redis 立即删键，限流形同虚设。
        RateLimitService service = new RateLimitService(null);
        assertThatThrownBy(() -> service.tryAcquire("rl:chat-session:2026-09-15:42", 10, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
