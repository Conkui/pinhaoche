package com.pinhaoche.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pinhaoche.common.api.ApiException;
import com.pinhaoche.common.api.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * 真实微信 jscode2session 实现。
 * 失败语义：网络异常/微信返回 errcode 时抛 UNAUTHORIZED，由登录接口转 401，
 * 不向客户端暴露微信原始错误细节。
 */
public class WxJsCode2SessionGateway implements WeChatGateway {

    private static final Logger log = LoggerFactory.getLogger(WxJsCode2SessionGateway.class);

    private final String appid;
    private final String secret;
    private final RestTemplate restTemplate;

    public WxJsCode2SessionGateway(String appid, String secret, RestTemplate restTemplate) {
        this.appid = appid;
        this.secret = secret;
        this.restTemplate = restTemplate;
    }

    public record WxSessionResponse(String openid, String session_key,
                                    @JsonProperty("unionid") String unionid,
                                    Integer errcode, String errmsg) {
    }

    @Override
    public String codeToOpenid(String code) {
        String url = "https://api.weixin.qq.com/sns/jscode2session?appid=%s&secret=%s&js_code=%s&grant_type=authorization_code"
                .formatted(appid, secret, code);
        try {
            WxSessionResponse resp = restTemplate.getForObject(url, WxSessionResponse.class);
            if (resp == null || resp.errcode() != null && resp.errcode() != 0 || resp.openid() == null) {
                log.warn("微信登录失败 errcode={} errmsg={}",
                        resp == null ? "?" : resp.errcode(), resp == null ? "?" : resp.errmsg());
                throw ApiException.unauthorized("微信登录失败，请重试");
            }
            return resp.openid();
        } catch (RestClientException e) {
            log.warn("微信登录网络异常: {}", e.getMessage());
            throw ApiException.unauthorized("微信登录服务暂不可用，请稍后重试");
        }
    }
}
