package com.pinhaoche.infrastructure.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Outbox 投递器（规范 §22）：定时扫描 PENDING 事件发到 RabbitMQ。
 * 设计要点：
 * - 业务事务只写 outbox，本组件负责投递，核心业务不依赖 MQ 可用性。
 * - 先标记 PUBLISHED 再发送可能丢消息（MQ 写失败）；先发送后标记可能重复投递。
 *   选择后者：消费者必须支持重复消费（幂等消费），丢通知比丢业务轻。
 * - 发送失败不标记，下一轮重试，天然具备重投能力。
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final String exchange;

    public OutboxPublisher(OutboxEventRepository repository, RabbitTemplate rabbitTemplate,
                           @Value("${phc.rabbit.exchange}") String exchange) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:500}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> pending = repository.findTop100ByStatusOrderByIdAsc("PENDING");
        for (OutboxEvent event : pending) {
            try {
                // routing key 即 eventType，消费者按类型订阅
                rabbitTemplate.convertAndSend(exchange, event.getEventType(), event.getPayload());
                event.markPublished();
            } catch (RuntimeException e) {
                // 单条失败不阻断本轮其余事件；MQ 故障时每轮快速失败，等恢复后补投
                log.warn("outbox 事件投递失败 id={} type={}: {}", event.getId(), event.getEventType(), e.getMessage());
                break;
            }
        }
    }
}
