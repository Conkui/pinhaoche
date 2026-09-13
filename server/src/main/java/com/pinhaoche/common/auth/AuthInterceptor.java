package com.pinhaoche.common.auth;

import com.pinhaoche.common.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * API 鉴权拦截器：身份只从 token 解析（docs/ARCHITECTURE.md 安全约定），
 * 永不接受客户端传入的 ownerId/senderId 等身份字段。
 * 解析出的 userId 放入 request attribute，供 controller/service 使用。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_USER_ID = "phc.userId";

    private final TokenStore tokenStore;

    public AuthInterceptor(TokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String authorization = request.getHeader("Authorization");
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : null;
        Long userId = tokenStore.resolve(token);
        if (userId == null) {
            throw ApiException.unauthorized("登录已失效，请重新登录");
        }
        request.setAttribute(ATTR_USER_ID, userId);
        return true;
    }
}
