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

import java.time.Duration;

/**
 * 通知事件消费（订阅消息/冷却/在线判断/通知事件消费）。
 * MessageReceived 保持既有冷却语义；RideMemberJoined 在成功加入后通知对应车主。
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
    private final org.springframework.data.redis.core.StringRedisTemplate redis;

    public NotificationConsumer(OnlinePresence presence, NotifyCooldownService cooldown,
                                NotificationGateway gateway, ChatMessageRepository messageRepository,
                                ChatService chatService, ObjectMapper objectMapper,
                                org.springframework.data.redis.core.StringRedisTemplate redis) {
        this.presence = presence;
        this.cooldown = cooldown;
        this.gateway = gateway;
        this.messageRepository = messageRepository;
        this.chatService = chatService;
        this.objectMapper = objectMapper;
        this.redis = redis;
    }

    @RabbitListener(queues = "${phc.rabbit.queue}")
    public void onNotificationEvent(String payload) {
        try {
            JsonNode event = objectMapper.readTree(payload);
            if (event.has("ownerId") && event.has("rideId") && event.has("userId")) {
                onRideMemberJoined(event);
            } else {
                onMessageReceived(event);
            }
        } catch (Exception e) {
            // 消费失败：日志 + 不 ack 抛出让 MQ 重投（消费者支持重复消费）
            log.error("通知事件消费失败 payload={}", payload, e);
            throw new IllegalStateException("通知消费失败", e);
        }
    }

    /** 聊天消息提醒：保留既有的在线判断和五分钟冷却语义。 */
    private void onMessageReceived(JsonNode event) {
        long sessionId = event.path("sessionId").asLong();
        long messageId = event.path("messageId").asLong();
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
    }

    /**
     * 有人成功加入后的车主提醒。系统不伪造 ChatMessage，避免把系统事件冒充为用户发言；
     * 也不复用聊天的冷却 key，确保每个成功加入都能触发一次通知网关调用。
     */
    private void onRideMemberJoined(JsonNode event) {
        long rideId = event.path("rideId").asLong();
        long ownerId = event.path("ownerId").asLong();
        long memberId = event.path("userId").asLong();
        if (rideId <= 0 || ownerId <= 0 || memberId <= 0 || ownerId == memberId) {
            throw new IllegalArgumentException("RideMemberJoined 事件字段非法");
        }
        String dedupKey = "notify:ride-joined:%d:%d".formatted(rideId, memberId);
        // Outbox/MQ 为至少一次投递；用"订单+成员"这个一次成功加入的稳定业务键去重。
        // 网关拒绝时删键并抛出，使消息重投仍有机会送达。
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(dedupKey, "1", Duration.ofDays(30)))) {
            return;
        }
        var result = gateway.send(new NotificationGateway.NotificationCommand(
                ownerId, rideId, 0L, "有同学加入了你的拼车，请在“我的发布”中联系对方"));
        if (!result.accepted()) {
            redis.delete(dedupKey);
            // 加入已提交，网关暂不可用不能回滚事实；抛出让 MQ 按至少一次语义重投。
            throw new IllegalStateException("加入提醒未受理: " + result.reason());
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

        @Bean
        public Binding rideMemberJoinedBinding(Queue notifyQueue,
                                               org.springframework.amqp.core.TopicExchange outboxExchange) {
            return BindingBuilder.bind(notifyQueue).to(outboxExchange).with("RideMemberJoined");
        }
    }
}
