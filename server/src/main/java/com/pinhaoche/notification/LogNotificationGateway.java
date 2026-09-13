package com.pinhaoche.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 开发用通知网关：仅记录日志，不发送真实微信订阅消息。
 * 生产实现（真实订阅消息）尚未完成——Not Completed，需要小程序模板 id 与授权流程；
 * 本类绝不冒充生产功能，行为在日志中明确标记 DEV-ONLY。
 */
@Component
public class LogNotificationGateway implements NotificationGateway {

    private static final Logger log = LoggerFactory.getLogger(LogNotificationGateway.class);

    @Override
    public NotificationResult send(NotificationCommand command) {
        log.info("[DEV-ONLY 微信订阅消息] receiver={} session={} message={} preview={}",
                command.receiverId(), command.sessionId(), command.messageId(), command.preview());
        return new NotificationResult(true, "DEV gateway logged");
    }
}
