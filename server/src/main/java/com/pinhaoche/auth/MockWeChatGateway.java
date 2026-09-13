package com.pinhaoche.auth;

/**
 * 本地/联调用 Mock 网关：code 即 openid。
 * 仅供开发环境（app.wechat.mock=true），生产必须关闭；不能把 mock 冒充真实登录能力。
 */
public class MockWeChatGateway implements WeChatGateway {

    @Override
    public String codeToOpenid(String code) {
        if (code == null || code.isBlank()) {
            throw com.pinhaoche.common.api.ApiException.validation("code 不能为空");
        }
        return "mock_" + code;
    }
}
