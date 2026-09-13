package com.pinhaoche.common.auth;

import com.pinhaoche.common.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 路由鉴权配置：
 * - /api/v1/auth/login：匿名（登录本身无 token）。
 * - /api/v1/admin/**：不走用户 token，用 X-Admin-Key 保护（RFC-005），由 AdminKeyInterceptor 校验。
 * - 其余 /api/**：必须携带用户 token。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final String adminKey;

    public WebConfig(AuthInterceptor authInterceptor, @Value("${app.admin-key}") String adminKey) {
        this.authInterceptor = authInterceptor;
        this.adminKey = adminKey;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminKeyInterceptor())
                .addPathPatterns("/api/v1/admin/**");
        registry.addInterceptor(authInterceptor)
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
