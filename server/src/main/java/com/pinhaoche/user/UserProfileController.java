package com.pinhaoche.user;

import com.pinhaoche.common.api.ApiException;
import com.pinhaoche.common.auth.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户资料接口（RFC-011）：PUT /api/v1/users/me。
 *
 * 为什么用 PUT 而不是 PATCH：小程序 request 封装的 method 联合类型只有 GET/POST/PUT/DELETE，
 * 且"设置资料"本身是幂等替换语义（同内容重复提交结果一致），用 PUT 无需改动小程序公共代码。
 *
 * 鉴权：/api/v1/** 已被 AuthInterceptor 覆盖，身份只从 request attribute 读取；
 * 请求体里没有（也不接受）任何用户 id 字段——即便客户端多传 id，Default Jackson 会忽略未知属性，
 * 身份不会被污染（认证身份只来自 token，见 AGENTS.md 安全约定）。
 */
@RestController
@RequestMapping("/api/v1")
public class UserProfileController {

    private final UserProfileService userProfileService;

    public UserProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @PutMapping("/users/me")
    public UserProfileService.UserProfile update(
            @RequestBody(required = false) UserProfileService.UpdateProfileRequest request,
            HttpServletRequest http) {
        Object userId = http.getAttribute(AuthInterceptor.ATTR_USER_ID);
        if (userId == null) {
            // 兜底：正常请求已被 AuthInterceptor 挡在 /api/v1/** 之外，这里只防御
            // "拦截器配置被误改/路径被挪出前缀"导致的匿名改他人资料。
            throw ApiException.unauthorized("未登录");
        }
        if (request == null) {
            // openapi 里 requestBody 是 required；这里显式声明 required = false 再自己判空，
            // 是为了让"完全没有 body"的请求也返回契约里的 400 ApiError，
            // 而不是 Spring 默认的非契约错误体（GlobalExceptionHandler 只覆盖 ApiException 与校验异常）。
            throw ApiException.validation("昵称或头像至少需要提供一项");
        }
        return userProfileService.updateProfile((Long) userId, request);
    }
}
