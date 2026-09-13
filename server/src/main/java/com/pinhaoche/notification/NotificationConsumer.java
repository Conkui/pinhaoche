package com.pinhaoche.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pinhaoche.chat.ChatMessageRepository;
import com.pinhaoche.chat.ChatService;
import com.pinhaoche.chat.OnlinePresence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * MessageReceived 事件消费（Agent D 边界：订阅消息/冷却/在线判断/通知事件消费）。
 * 消费语义：接收者不在当前聊天页 且 冷却期外 → 发送微信订阅消息（规范 §14/§15）。
 * 重复消费安全：先查在线与冷却（Redis NX 天然幂等），重复投递不会产生重复微信提醒。
 */
@Component
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final OnlinePresence presence;
    private final NotifyCooldownService cooldown;
    private final NotificationGateway gateway;
    private final ChatMessageRepository messageRepository;
    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    public NotificationConsumer(OnlinePresence presence, NotifyCooldownService cooldown,
                                NotificationGateway gateway, ChatMessageRepository messageRepository,
                                ChatService chatService, ObjectMapper objectMapper) {
        this.presence = presence;
        this.cooldown = cooldown;
        this.gateway = gateway;
        this.messageRepository = messageRepository;
        this.chatService = chatService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = "${phc.rabbit.queue}")
    public void onMessageReceived(String payload) {
        try {
            JsonNode event = objectMapper.readTree(payload);
            long sessionId = event.path("sessionId").asLong();
            long messageId = event.path("messageId").asLong();
            long senderId = event.path("senderId").asLong();
            long receiverId = event.path("receiverId").asLong();

            // 在线判断兜底：推送路径已处理在页用户；这里仍可能重放，isInSession 幂等
            if (presence.isInSession(receiverId, sessionId)) {
                return;
            }
            // 冷却期内只累加未读，不发订阅消息（规范 §15）
            if (!cooldown.tryAcquire(sessionId, receiverId)) {
                return;
            }
            String preview = messageRepository.findById(messageId)
                    .map(m -> m.getContent()).orElse("");
            // 未读数此时已由 WS 路径累加；这里触发微信级提醒
            var result = gateway.send(new NotificationGateway.NotificationCommand(
                    receiverId, sessionId, messageId, preview));
            if (!result.accepted()) {
                log.warn("订阅消息发送未受理 receiver={} reason={}", receiverId, result.reason());
                chatService.incrementUnread(sessionId, receiverId);
            }
        } catch (Exception e) {
            // 消费失败：日志 + 不 ack 抛出让 MQ 重投（消费者支持重复消费）
            log.error("MessageReceived 消费失败 payload={}", payload, e);
            throw new IllegalStateException("通知消费失败", e);
        }
    }

    /** 队列绑定声明（仅 notification 关心的事件类型）。 */
    @Configuration
    public static class QueueConfig {

        private final String queue;
        private final String exchange;

        public QueueConfig(@Value("${phc.rabbit.queue}") String queue,
                           @Value("${phc.rabbit.exchange}") String exchange) {
            this.queue = queue;
            this.exchange = exchange;
        }

        @Bean
        public Queue notifyQueue() {
            return new Queue(queue, true);
        }

        @Bean
        public Binding notifyBinding(Queue notifyQueue, org.springframework.amqp.core.TopicExchange outboxExchange) {
            return BindingBuilder.bind(notifyQueue).to(outboxExchange).with("MessageReceived");
        }
    }
}
