package com.pinhaoche.common.security;

import com.pinhaoche.common.api.ApiException;
import com.pinhaoche.common.api.ErrorCode;
import com.pinhaoche.common.auth.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * RFC-010 的两个限流拦截器（同一文件便于对照维护，二者只差"身份来源"与"阈值"）：
 * - {@link LoginRateLimit}：登录接口按来源 IP 限 20 次/分钟（登录页匿名、没有 userId，只能按 IP）。
 * - {@link UserRateLimit}：其余 /api/v1/** 按登录用户限 120 次/分钟。
 * 键规则 rl:ip:{窗口}:{ip} / rl:u:{窗口}:{userId}，窗口号 = epochMilli / 60000（固定 1 分钟）。
 * 计数与判定统一走 {@link RateLimitService}，Redis 故障时 fail-open（取舍见该类注释）。
 */
public final class RateLimitInterceptors {

    private RateLimitInterceptors() {
    }

    /** 登录接口限流：防撞库/刷 code（RFC-010）。 */
    public static class LoginRateLimit implements HandlerInterceptor {

        static final String PREFIX = "rl:ip";
        static final int LIMIT_PER_MINUTE = 20;
        private static final String MESSAGE = "请求过于频繁，请稍后再试";

        private final RateLimitService rateLimitService;

        public LoginRateLimit(RateLimitService rateLimitService) {
            this.rateLimitService = rateLimitService;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            // 键只由服务端可得信息构成（IP 来自代理头/连接），绝不接受请求参数指定，防止伪造身份绕过限流
            String bucket = RateLimitService.windowKey(PREFIX, clientIp(request), System.currentTimeMillis());
            if (!rateLimitService.tryAcquire(bucket, LIMIT_PER_MINUTE)) {
                throw new ApiException(ErrorCode.RATE_LIMITED, MESSAGE);
            }
            return true;
        }

        /**
         * 客户端 IP：优先取 X-Forwarded-For 第一段——生产部署在 Nginx 反代之后，remoteAddr 是代理 IP，
         * 对所有用户相同会导致"一人超限全员超限"；XFF 第一段才是原始客户端。
         * 已知局限：多级代理下客户端可伪造后续追加段，但第一段由第一跳可信代理写入，V1 单层 Nginx 场景成立；
         * 无 XFF 头（本地直连/开发环境）回退 remoteAddr。
         */
        static String clientIp(HttpServletRequest request) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                int comma = xff.indexOf(',');
                String first = (comma > -1 ? xff.substring(0, comma) : xff).trim();
                if (!first.isEmpty()) {
                    return first;
                }
            }
            return request.getRemoteAddr();
        }
    }

    /** 已登录用户限流：防单账号高频刷接口（RFC-010）。 */
    public static class UserRateLimit implements HandlerInterceptor {

        static final String PREFIX = "rl:u";
        static final int LIMIT_PER_MINUTE = 120;
        private static final String MESSAGE = "请求过于频繁，请稍后再试";

        private final RateLimitService rateLimitService;

        public UserRateLimit(RateLimitService rateLimitService) {
            this.rateLimitService = rateLimitService;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            // 必须注册在 AuthInterceptor 之后：userId 只认 token 解析后写入的 request attribute
            // （与鉴权同一红线：不接受客户端自报身份）。attribute 不存在 → 直接放行：
            // 该拦截器虽与 Auth 同 patterns，但 admin/**（无 userId）等路径不应因限流误伤。
            Object attr = request.getAttribute(AuthInterceptor.ATTR_USER_ID);
            if (!(attr instanceof Long userId)) {
                return true;
            }
            String bucket = RateLimitService.windowKey(PREFIX, String.valueOf(userId), System.currentTimeMillis());
            if (!rateLimitService.tryAcquire(bucket, LIMIT_PER_MINUTE)) {
                throw new ApiException(ErrorCode.RATE_LIMITED, MESSAGE);
            }
            return true;
        }
    }
}
