package com.pinhaoche.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * WeChatGateway 装配：mock 开关决定走真实微信还是本地假登录。
 * 为什么放配置类而不是 @ConditionalOnProperty 在两个实现上——集中一处，避免开关漂移。
 */
@Configuration
public class AuthConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthConfig.class);

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.build();
    }

    @Bean
    public WeChatGateway weChatGateway(@Value("${app.wechat.mock:true}") boolean mock,
                                       @Value("${app.wechat.appid:}") String appid,
                                       @Value("${app.wechat.secret:}") String secret,
                                       RestTemplate restTemplate,
                                       ObjectMapper objectMapper) {
        if (mock) {
            return new MockWeChatGateway();
        }
        if (appid == null || appid.isBlank() || secret == null || secret.isBlank()) {
            // 启动即告警而不是等用户登录才 401：真实模式下漏配 AppSecret 时，微信会返回
            // errcode（40013/40125）而登录接口统一转 401，客户端只看到"微信登录失败，请重试"，
            // 排查者极易误判成小程序侧问题。这里把"配置不完整"这一根因提前暴露在启动日志里。
            // 不直接启动失败：容器起不来会让整套环境（含后台管理页）不可用，代价大于收益。
            log.warn("WECHAT_MOCK=false（真实微信模式）但 WECHAT_APPID/WECHAT_SECRET 未完整配置，"
                    + "所有登录都会失败并返回 401。请在仓库根 .env 里补齐后执行 "
                    + "docker compose up -d --build server（见 docs/RUNBOOK.md 第 6 节）");
        }
        return new WxJsCode2SessionGateway(appid, secret, restTemplate, objectMapper);
    }
}
