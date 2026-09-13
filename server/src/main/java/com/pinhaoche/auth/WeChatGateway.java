package com.pinhaoche.auth;

/**
 * 微信 jscode2session 网关接口（接口优先约定，docs/ARCHITECTURE.md）。
 * 应用层只依赖本接口：生产实现走真实微信 API，mock 实现供本地联调。
 * 禁止在业务代码里直接调用微信 SDK/HTTP。
 */
public interface WeChatGateway {

    /**
     * @param code wx.login() 获取的临时凭证
     * @return openid（会话密钥 V1 不需要保存，故不出现在返回值中）
     * @throws com.pinhaoche.common.api.ApiException 微信侧失败时抛 UNAUTHORIZED
     */
    String codeToOpenid(String code);
}
