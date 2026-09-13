package com.pinhaoche.chat;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;

/**
 * WebSocket 路由配置：/ws/v1/chat（docs/WEBSOCKET_PROTOCOL.md）。
 * 鉴权：握手阶段从 query ?token= 解析身份；解析失败拒绝握手。
 * 服务端以 token 身份覆盖任何客户端用户字段——客户端帧里根本没有身份字段。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final com.pinhaoche.common.auth.TokenStore tokenStore;

    public WebSocketConfig(ChatWebSocketHandler chatWebSocketHandler,
                           com.pinhaoche.common.auth.TokenStore tokenStore) {
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.tokenStore = tokenStore;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws/v1/chat")
                .addInterceptors(new HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                                   WebSocketHandler wsHandler, java.util.Map<String, Object> attributes) {
                        if (request instanceof ServletServerHttpRequest servlet) {
                            Long userId = tokenStore.resolve(
                                    servlet.getServletRequest().getParameter("token"));
                            if (userId != null) {
                                attributes.put(ChatWebSocketHandler.ATTR_USER_ID, userId);
                                return true;
                            }
                        }
                        return false;
                    }

                    @Override
                    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                               WebSocketHandler wsHandler, Exception exception) {
                    }
                })
                .setAllowedOriginPatterns("*");
    }
}
