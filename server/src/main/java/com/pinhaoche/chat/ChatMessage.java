package com.pinhaoche.chat;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** chat_message 表（V1__init.sql）：唯一键 (sender_id, client_msg_id) 是消息幂等的最终保障（规范 §12）。 */
@Entity
@Table(name = "chat_message")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long sessionId;
    private Long senderId;
    private String clientMsgId;
    /** V1 只有 TEXT（快捷文本也按普通 TEXT 保存，规范 §10） */
    private String type;
    private String content;
    private OffsetDateTime createdAt;

    public ChatMessage() {
    }

    public ChatMessage(long sessionId, long senderId, String clientMsgId, String type,
                       String content, OffsetDateTime now) {
        this.sessionId = sessionId;
        this.senderId = senderId;
        this.clientMsgId = clientMsgId;
        this.type = type;
        this.content = content;
        this.createdAt = now;
    }

    public Long getId() { return id; }
    public Long getSessionId() { return sessionId; }
    public Long getSenderId() { return senderId; }
    public String getClientMsgId() { return clientMsgId; }
    public String getType() { return type; }
    public String getContent() { return content; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
