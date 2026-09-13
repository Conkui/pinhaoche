package com.pinhaoche.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 基础设施装配：RabbitMQ 交换器与共享 ObjectMapper。
 * 队列由 notification 模块按自己消费的 eventType 声明绑定，保持模块边界。
 */
@Configuration
public class InfrastructureConfig {

    @Bean
    public TopicExchange outboxExchange(@Value("${phc.rabbit.exchange}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
