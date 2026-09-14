package com.pinhaoche.common.auth;

import com.pinhaoche.common.api.ApiException;
import com.pinhaoche.common.security.RateLimitInterceptors;
import com.pinhaoche.common.security.RateLimitService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 路由鉴权配置：
 * - /api/v1/auth/login：匿名（登录本身无 token），按 IP 限流 20 次/分钟（RFC-010）。
 * - /api/v1/admin/**：不走用户 token，用 X-Admin-Key 保护（RFC-005），由 AdminKeyInterceptor 校验。
 * - 其余 /api/**：必须携带用户 token，按登录用户限流 120 次/分钟（RFC-010）。
 * 执行顺序 = 注册顺序（同 order 值时注册表按插入序稳定排序）：限流必须"登录在鉴权前（匿名无 userId）、
 * 用户级在鉴权后（userId attribute 由 AuthInterceptor 写入）"。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final RateLimitService rateLimitService;
    private final String adminKey;

    public WebConfig(AuthInterceptor authInterceptor, RateLimitService rateLimitService,
                     @Value("${app.admin-key}") String adminKey) {
        this.authInterceptor = authInterceptor;
        this.rateLimitService = rateLimitService;
        this.adminKey = adminKey;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminKeyInterceptor())
                .addPathPatterns("/api/v1/admin/**");
        // RFC-010 登录限流：只挂登录接口（匿名，按 IP），必须在业务逻辑前挡住撞库/刷 code。
        registry.addInterceptor(new RateLimitInterceptors.LoginRateLimit(rateLimitService))
                .addPathPatterns("/api/v1/auth/login");
        // 鉴权先执行：解析 token 并把 userId 写入 request attribute（"phc.userId"）。
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/auth/login", "/api/v1/admin/**");
        // RFC-010 用户限流：必须在 AuthInterceptor 之后（依赖其写入的 userId attribute）。
        // 与 Auth 同 patterns：登录接口由 IP 限流覆盖（此处无 userId、内部直接放行），
        // admin/** 不按用户限流，两者显式排除避免空转。
        registry.addInterceptor(new RateLimitInterceptors.UserRateLimit(rateLimitService))
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/auth/login", "/api/v1/admin/**");
    }

    /** 管理端密钥校验：为什么不用用户 token——admin 是运营后台身份，与微信用户体系隔离。 */
    public class AdminKeyInterceptor implements org.springframework.web.servlet.HandlerInterceptor {
        @Override
        public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                                 jakarta.servlet.http.HttpServletResponse response, Object handler) {
            if (!adminKey.equals(request.getHeader("X-Admin-Key"))) {
                throw ApiException.forbidden("管理密钥无效");
            }
            return true;
        }
    }
}
