package com.pinhaoche.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * WeChatGateway 装配：mock 开关决定走真实微信还是本地假登录。
 * 为什么放配置类而不是 @ConditionalOnProperty 在两个实现上——集中一处，避免开关漂移。
 */
@Configuration
public class AuthConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.build();
    }

    @Bean
    public WeChatGateway weChatGateway(@Value("${app.wechat.mock:true}") boolean mock,
                                       @Value("${app.wechat.appid:}") String appid,
                                       @Value("${app.wechat.secret:}") String secret,
                                       RestTemplate restTemplate) {
        if (mock) {
            return new MockWeChatGateway();
        }
        return new WxJsCode2SessionGateway(appid, secret, restTemplate);
    }
}
