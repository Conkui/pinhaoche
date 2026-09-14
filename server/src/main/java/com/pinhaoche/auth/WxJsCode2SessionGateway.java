package com.pinhaoche.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pinhaoche.common.api.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

/**
 * 真实微信 jscode2session 实现。
 * 失败语义：网络异常/响应不可解析/微信返回 errcode 时抛 UNAUTHORIZED，由登录接口转 401，
 * 不向客户端暴露微信原始错误细节（errcode/errmsg 只进服务端日志，供运维定位）。
 */
public class WxJsCode2SessionGateway implements WeChatGateway {

    private static final Logger log = LoggerFactory.getLogger(WxJsCode2SessionGateway.class);

    private final String appid;
    private final String secret;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public WxJsCode2SessionGateway(String appid, String secret, RestTemplate restTemplate,
                                   ObjectMapper objectMapper) {
        this.appid = appid;
        this.secret = secret;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 微信 jscode2session 响应体。
     *
     * 为什么必须 ignoreUnknown：本应用只有 InfrastructureConfig 里那个占位 `@Primary ObjectMapper`
     * （裸 `new ObjectMapper()`，FAIL_ON_UNKNOWN_PROPERTIES 默认开启）。微信对第三方响应**只增不减**，
     * 一旦它多返回一个字段（如历史上的 `unionid`、将来的风控字段），严格映射就会抛
     * UnrecognizedPropertyException → 被下面的 catch 转成 401 → **所有真实登录瞬间全量失败**，
     * 而错误日志只有"响应无法解析（length=N）"，无线索可查（与本次 text/plain 缺陷同一类失败模式）。
     * 忽略未知字段后，微信加字段不再影响登录；真正需要的字段缺失仍会被下面的判空拦住。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WxSessionResponse(String openid, String session_key,
                                    @JsonProperty("unionid") String unionid,
                                    Integer errcode, String errmsg) {
    }

    @Override
    public String codeToOpenid(String code) {
        String url = "https://api.weixin.qq.com/sns/jscode2session?appid=%s&secret=%s&js_code=%s&grant_type=authorization_code"
                .formatted(appid, secret, code);

        // 为什么用 String 接收、再用 ObjectMapper 显式解析，而不是直接映射成 WxSessionResponse：
        // 微信 jscode2session 的响应头是 `Content-Type: text/plain`（成功与 errcode 错误响应都是），
        // 而 RestTemplate 默认的 Jackson 转换器只处理 application/json，直接映射会抛
        //   "no suitable HttpMessageConverter found for response type [WxSessionResponse]
        //    and content type [text/plain]"
        // 并被下面的 catch 统一转成 401 —— 结果是**即使 AppSecret 完全正确，真实微信登录也永远失败**
        // （mock 模式不走本类，本地联调与既有单测都发现不了；只有真实模式实测才会暴露）。
        // 先用 StringHttpMessageConverter 取原始文本，再显式解析 JSON，使结果与响应头无关。
        //
        // 为什么用 byte[] 而不是 String 接收：微信的 text/plain 响应**不带 charset**，
        // StringHttpMessageConverter 会用它自己的默认字符集解码，导致 UTF-8 的中文 errmsg
        // 变成 `ç³»ç»ç¹å¿` 这样的乱码——功能不受影响（openid/errcode 都是 ASCII），
        // 但真实模式下排查"微信为什么拒绝登录"时，日志里最有价值的一句中文提示恰好不可读。
        // byte[] 绕开字符串解码，交给 Jackson 按 BOM/启发式识别 UTF-8。
        byte[] body;
        try {
            body = restTemplate.getForObject(url, byte[].class);
        } catch (RestClientException e) {
            log.warn("微信登录网络异常: {}", mask(e.getMessage()));
            throw ApiException.unauthorized("微信登录服务暂不可用，请稍后重试");
        }

        WxSessionResponse resp;
        try {
            resp = objectMapper.readValue(body, WxSessionResponse.class);
        } catch (IOException | IllegalArgumentException e) {
            // 只记长度不记原文：成功响应体含 session_key（会话密钥），失败响应体虽无密钥，
            // 但"解析失败的原文"内容无法预判，统一不落日志，避免把密钥写进日志文件。
            log.warn("微信登录响应无法解析（length={}）: {}", body == null ? 0 : body.length,
                    mask(e.getMessage()));
            throw ApiException.unauthorized("微信登录服务暂不可用，请稍后重试");
        }

        if (resp == null || (resp.errcode() != null && resp.errcode() != 0) || resp.openid() == null) {
            log.warn("微信登录失败 errcode={} errmsg={}",
                    resp == null ? "?" : resp.errcode(), resp == null ? "?" : resp.errmsg());
            throw ApiException.unauthorized("微信登录失败，请重试");
        }
        return resp.openid();
    }

    /**
     * 日志脱敏：请求 URL 里带 secret 与 js_code，而 RestClientException 的消息通常会带上完整 URI。
     * 直接打日志等于把 AppSecret 写进日志文件（可被日志聚合/截图泄露），故在出口统一打码。
     * appid 不算机密（它在 project.config.json 里公开），保留原样便于排查"appid 与 secret 是否配对"。
     */
    String mask(String text) {
        if (text == null) {
            return "";
        }
        String masked = text;
        if (secret != null && !secret.isBlank()) {
            masked = masked.replace(secret, "***");
        }
        return masked.replaceAll("js_code=[^&\\s]*", "js_code=***");
    }
}
