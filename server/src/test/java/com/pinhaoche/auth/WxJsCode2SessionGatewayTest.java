package com.pinhaoche.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pinhaoche.common.api.ApiException;
import com.pinhaoche.common.api.ErrorCode;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 真实微信登录网关的回归测试（RFC-011 批次实测发现缺陷后补）。
 *
 * 存在的意义：微信 jscode2session 的响应头是 `Content-Type: text/plain`，
 * 而 RestTemplate 默认的 Jackson 转换器只处理 application/json。旧实现直接
 * `getForObject(url, WxSessionResponse.class)`，会抛
 * "no suitable HttpMessageConverter found ... content type [text/plain]" 并被转成 401，
 * 于是**即使 AppSecret 完全正确，真实登录也永远失败**——而 mock 模式不走本类，
 * 既有单测又只覆盖 mock 网关，所以只有真实模式实测才会暴露。
 * 本测试用 `MediaType.TEXT_PLAIN` 声明响应头，正是把这条缺陷钉死：
 * 若有人把实现改回"直接映射成 DTO"或换成只认 application/json 的写法，这里立刻失败。
 */
class WxJsCode2SessionGatewayTest {

    private static final String APPID = "wxe956226c7e7fa726";
    private static final String SECRET = "s3cr3t-appsecret-value";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        // 不调用 RestTemplateBuilder：默认转换器集合与生产一致（不含任何为测试放宽的配置），
        // 否则测试可能"因为多加了一个转换器"而通过，失去回归意义。
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    private WxJsCode2SessionGateway gateway() {
        return new WxJsCode2SessionGateway(APPID, SECRET, restTemplate, new ObjectMapper());
    }

    @Test
    @DisplayName("text/plain 响应头也能解析出 openid（真实微信的实际响应形态）")
    void parsesOpenidFromTextPlainResponse() {
        server.expect(requestTo(containsString("/sns/jscode2session")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"openid\":\"oXyZ1234567890\",\"session_key\":\"sk\",\"unionid\":\"u1\"}",
                        MediaType.TEXT_PLAIN));

        assertEquals("oXyZ1234567890", gateway().codeToOpenid("wx-login-code"));
        server.verify();
    }

    @Test
    @DisplayName("application/json 响应头同样可解析（不把实现绑死在某一种头部上）")
    void parsesOpenidFromJsonResponse() {
        server.expect(requestTo(containsString("/sns/jscode2session")))
                .andRespond(withSuccess("{\"openid\":\"oJson\"}", MediaType.APPLICATION_JSON));

        assertEquals("oJson", gateway().codeToOpenid("code"));
        server.verify();
    }

    @Test
    @DisplayName("appid/secret/js_code/grant_type 必须都在请求里（微信要求的参数一个不能少）")
    void sendsAllRequiredQueryParameters() {
        server.expect(requestTo(containsString("appid=" + APPID)))
                .andExpect(requestTo(containsString("secret=" + SECRET)))
                .andExpect(requestTo(containsString("js_code=the-code")))
                .andExpect(requestTo(containsString("grant_type=authorization_code")))
                .andRespond(withSuccess("{\"openid\":\"o1\"}", MediaType.TEXT_PLAIN));

        gateway().codeToOpenid("the-code");
        server.verify();
    }

    @Test
    @DisplayName("微信返回 errcode（如 AppSecret 错误 40125）→ UNAUTHORIZED，不把 errcode 透给客户端")
    void errcodeBecomesUnauthorized() {
        server.expect(requestTo(containsString("/sns/jscode2session")))
                .andRespond(withSuccess("{\"errcode\":40125,\"errmsg\":\"invalid appsecret\"}",
                        MediaType.TEXT_PLAIN));

        ApiException e = assertThrows(ApiException.class, () -> gateway().codeToOpenid("code"));
        assertEquals(ErrorCode.UNAUTHORIZED, e.getCode());
        assertFalse(e.getMessage().contains("40125"), "微信原始 errcode 不应出现在给客户端的文案里");
    }

    @Test
    @DisplayName("响应体不是 JSON（网关返回 HTML/空体）→ UNAUTHORIZED，不抛 500")
    void unparsableBodyBecomesUnauthorized() {
        server.expect(requestTo(containsString("/sns/jscode2session")))
                .andRespond(withSuccess("<html>bad gateway</html>", MediaType.TEXT_PLAIN));

        ApiException e = assertThrows(ApiException.class, () -> gateway().codeToOpenid("code"));
        assertEquals(ErrorCode.UNAUTHORIZED, e.getCode());
    }

    @Test
    @DisplayName("微信新增未知字段仍能登录（防严格 ObjectMapper 让真实登录全量 401）")
    void ignoresUnknownFieldsAddedByWeChat() {
        // 本应用只有 InfrastructureConfig 里那个裸 new ObjectMapper() 的 @Primary mapper，
        // FAIL_ON_UNKNOWN_PROPERTIES 默认开启；微信对第三方响应只增不减，
        // 若 DTO 不声明 ignoreUnknown，微信加一个字段就会让所有真实登录 401（Review 第二轮发现）。
        // 这里用 new ObjectMapper()（= 生产同一套严格默认值）钉死该行为。
        server.expect(requestTo(containsString("/sns/jscode2session")))
                .andRespond(withSuccess(
                        "{\"openid\":\"oNewField\",\"session_key\":\"sk\",\"new_risk_field\":\"x\"}",
                        MediaType.TEXT_PLAIN));

        assertEquals("oNewField", gateway().codeToOpenid("code"));
        server.verify();
    }

    @Test
    @DisplayName("UTF-8 中文 errmsg 在日志里可读（微信 text/plain 不带 charset）")
    void chineseErrmsgStaysReadableInLog() {
        // 用 byte[] 接收的意义就在这一条：String 接收时 StringHttpMessageConverter 会按自身默认
        // 字符集解码 UTF-8 字节，日志里的中文提示变成 `ç³»ç»ç¹å¿`，真实模式下排查"微信为什么拒绝登录"
        // 时最有价值的一句中文恰好不可读。这里直接断言日志文本。
        ch.qos.logback.classic.Logger gatewayLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(WxJsCode2SessionGateway.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        gatewayLogger.addAppender(appender);
        try {
            String json = "{\"errcode\":-1,\"errmsg\":\"系统繁忙，请稍后重试\"}";
            server.expect(requestTo(containsString("/sns/jscode2session")))
                    .andRespond(withSuccess(json.getBytes(StandardCharsets.UTF_8), MediaType.TEXT_PLAIN));

            assertThrows(ApiException.class, () -> gateway().codeToOpenid("code"));

            String logged = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertTrue(logged.contains("系统繁忙"), "日志中的中文 errmsg 必须是可读 UTF-8，实际日志=" + logged);
            assertTrue(logged.contains("errcode=-1"), "errcode 必须进日志便于定位，实际日志=" + logged);
        } finally {
            gatewayLogger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("日志脱敏：URL 里的 secret 与 js_code 不得出现在日志文本中")
    void masksSecretAndCodeInLogText() {
        String raw = "I/O error on GET request for \"https://api.weixin.qq.com/sns/jscode2session"
                + "?appid=" + APPID + "&secret=" + SECRET + "&js_code=abc123&grant_type=authorization_code\"";

        String masked = gateway().mask(raw);

        assertFalse(masked.contains(SECRET), "AppSecret 必须被打码");
        assertFalse(masked.contains("abc123"), "js_code 必须被打码");
        // appid 非机密，保留以便排查"appid 与 secret 是否配对"
        assertTrueContains(masked, APPID);
    }

    private static void assertTrueContains(String text, String expected) {
        org.junit.jupiter.api.Assertions.assertTrue(text.contains(expected),
                "日志里应保留 appid 便于排查: " + text);
    }
}
