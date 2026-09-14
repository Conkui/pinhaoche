package com.pinhaoche.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pinhaoche.common.api.ApiException;
import com.pinhaoche.common.idempotency.IdempotencyService;
import com.pinhaoche.common.util.TextSanitizer;
import com.pinhaoche.infrastructure.outbox.OutboxEvent;
import com.pinhaoche.infrastructure.outbox.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Chat 应用服务（Agent C 边界：会话/消息/WebSocket/ACK/未读/断线恢复/30天记录；
 * 不实现微信订阅通知——那是 notification 模块经 outbox 事件异步完成）。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int CONTENT_MAX = 2000;
    private static final int HISTORY_LIMIT = 200;

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final OnlinePresence presence;
    private final IdempotencyService idempotency;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;
    private final org.springframework.transaction.support.TransactionTemplate txTemplate;

    public ChatService(ChatSessionRepository sessionRepository, ChatMessageRepository messageRepository,
                       OnlinePresence presence, IdempotencyService idempotency,
                       OutboxEventRepository outboxRepository, ObjectMapper objectMapper,
                       org.springframework.data.redis.core.StringRedisTemplate redis,
                       org.springframework.transaction.support.TransactionTemplate txTemplate) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.presence = presence;
        this.idempotency = idempotency;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.redis = redis;
        this.txTemplate = txTemplate;
    }

    // ---------- DTO ----------

    public record ChatSessionDto(long id, long rideId, long peerUserId, int unreadCount) {
    }

    public record ChatSessionSummaryDto(long id, long rideId, long peerUserId, int unreadCount,
                                        String lastMessagePreview, OffsetDateTime lastMessageAt) {
    }

    public record MessageDto(long id, long sessionId, long senderId, String clientMsgId,
                             String type, String content, OffsetDateTime createdAt)
            implements IdempotencyService.Resource {
    }

    // ---------- 会话 ----------

    /**
     * 获取或创建会话（规范 §10：有 → 复用，无 → 创建）。
     * 为什么排序存储 user_low/high：使 (ride, a, b) 与 (ride, b, a) 归一化为同一行，依赖唯一键兜底并发。
     */
    @Transactional
    public ChatSessionDto getOrCreateSession(long userId, long rideId, long peerUserId) {
        if (userId == peerUserId) {
            throw ApiException.validation("不能与自己建立会话");
        }
        long low = Math.min(userId, peerUserId);
        long high = Math.max(userId, peerUserId);
        ChatSession session = sessionRepository.findByRideIdAndUserLowIdAndUserHighId(rideId, low, high)
                .orElseGet(() -> sessionRepository.save(new ChatSession(rideId, low, high, OffsetDateTime.now())));
        return new ChatSessionDto(session.getId(), rideId, peerUserId, unreadCount(session.getId(), userId));
    }

    @Transactional(readOnly = true)
    public ChatSession mustGetSession(long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> ApiException.notFound("会话不存在"));
    }

    /** 会话列表（RFC-001）：含未读数与最后一条消息预览。 */
    @Transactional(readOnly = true)
    public List<ChatSessionSummaryDto> listSessions(long userId) {
        return sessionRepository.findByUserLowIdOrUserHighIdOrderByUpdatedAtDesc(userId, userId).stream()
                .map(s -> {
                    long peer = s.peerOf(userId);
                    var last = messageRepository.findFirstBySessionIdOrderByIdDesc(s.getId());
                    return new ChatSessionSummaryDto(s.getId(), s.getRideId(), peer,
                            unreadCount(s.getId(), userId),
                            last.map(ChatMessage::getContent).orElse(null),
                            last.map(ChatMessage::getCreatedAt).orElse(null));
                }).toList();
    }

    // ---------- 消息 ----------

    /** 增量历史（断线补偿）：GET messages?afterId={lastMessageId}（docs/WEBSOCKET_PROTOCOL.md）。 */
    @Transactional(readOnly = true)
    public List<MessageDto> messages(long sessionId, long userId, long afterId) {
        assertMember(sessionId, userId);
        return messageRepository
                .findBySessionIdAndIdGreaterThanOrderByIdAsc(sessionId, afterId, Pageable.ofSize(HISTORY_LIMIT))
                .stream().map(ChatService::toDto).toList();
    }

    /**
     * 发送消息（WebSocket SEND_MESSAGE 唯一入口）。
     * 顺序固定（docs/WEBSOCKET_PROTOCOL.md）：校验成员 → (sender_id, client_msg_id) 去重落库 →
     * 同事务写 outbox → 提交后才允许 ACK / 推送。禁止先推送后保存。
     * 重复 clientMsgId 返回同一条已持久化消息（幂等），不新建记录。
     *
     * 为什么用 TransactionTemplate 而不是 @Transactional：重复键异常会污染当前 Hibernate 会话
     * （"don't flush the Session after an exception occurs"），必须把"插入"隔离在独立事务里，
     * 捕获重复键后在干净的新事务中读取既有消息返回。
     */
    public MessageDto sendMessage(long sessionId, long senderId, String clientMsgId, String content) {
        ChatSession session = mustGetSession(sessionId);
        if (!session.hasMember(senderId)) {
            throw ApiException.forbidden("不是该会话成员");
        }
        if (clientMsgId == null || clientMsgId.isBlank() || clientMsgId.length() > 36) {
            throw ApiException.validation("clientMsgId 非法");
        }
        if (content == null || content.isBlank()) {
            throw ApiException.validation("消息内容不能为空");
        }
        if (content.length() > CONTENT_MAX) {
            throw ApiException.validation("消息内容超长（最多 %d 字）".formatted(CONTENT_MAX));
        }
        // RFC-010 输入净化：放在长度校验之后（按用户原始输入判定长度，避免剥离后长度语义漂移）、
        // 入库之前。为什么必须净化：消息会经 WebSocket 原样推给对方并长期存库，控制字符（0x00-0x1F
        // 中除 \n \t 外）无业务语义，却可伪造换行污染聊天记录渲染、破坏日志/存储链路。
        // 净化结果放新变量：参数 content 仍被下方事务 lambda 引用，必须是 effectively final。
        String sanitized = TextSanitizer.clean(content);
        if (sanitized.isBlank()) {
            // 极端情况：输入全是控制字符，净化后为空。仍按参数非法拒绝，不落空消息。
            throw ApiException.validation("消息内容不能为空");
        }

        try {
            // 独立事务：消息落库 + 会话时间戳 + outbox 事件（同事务提交，规范 §22）
            return txTemplate.execute(status -> {
                ChatMessage m = messageRepository.save(new ChatMessage(sessionId, senderId,
                        clientMsgId, "TEXT", sanitized, OffsetDateTime.now()));
                session.setUpdatedAt(OffsetDateTime.now());
                long receiverId = session.peerOf(senderId);
                emit(sessionId, m.getId(), senderId, receiverId);
                return toDto(m);
            });
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 网络重试 / 客户端重复发送：命中 (sender_id, client_msg_id) 唯一键，返回已有消息
            MessageDto existing = txTemplate.execute(status ->
                    messageRepository.findBySenderIdAndClientMsgId(senderId, clientMsgId)
                            .map(ChatService::toDto).orElse(null));
            if (existing == null) {
                throw e;
            }
            log.info("消息幂等命中 sender={} clientMsgId={}", senderId, clientMsgId);
            return existing;
        }
    }

    // ---------- 未读 ----------

    /** 未读数只存 Redis（规范 §13：Redis 只负责在线状态/未读数/路由/冷却，MySQL 为最终事实）。 */
    public int unreadCount(long sessionId, long userId) {
        try {
            String v = redis.opsForValue().get("chat:unread:%d:%d".formatted(sessionId, userId));
            return v == null ? 0 : Integer.parseInt(v);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** 进入聊天页阅读后未读清零，允许下一轮微信提醒（规范 §15）。 */
    @Transactional
    public void markRead(long sessionId, long userId) {
        assertMember(sessionId, userId);
        try {
            redis.delete("chat:unread:%d:%d".formatted(sessionId, userId));
        } catch (RuntimeException ignored) {
        }
    }

    /** 由 WebSocket 推送路径调用：接收者不在当前聊天页时累加未读（冷却由 notification 模块负责）。 */
    public void incrementUnread(long sessionId, long userId) {
        try {
            redis.opsForValue().increment("chat:unread:%d:%d".formatted(sessionId, userId));
        } catch (RuntimeException e) {
            log.warn("未读数累加失败（Redis 异常）session={} user={}", sessionId, userId);
        }
    }

    // ---------- 内部 ----------

    /** WebSocket ENTER_SESSION 用：非成员直接拒绝（协议约定返回 ERROR，不静默丢失）。 */
    @Transactional(readOnly = true)
    public void assertMemberForWs(long sessionId, long userId) {
        assertMember(sessionId, userId);
    }

    private void assertMember(long sessionId, long userId) {
        ChatSession session = mustGetSession(sessionId);
        if (!session.hasMember(userId)) {
            throw ApiException.forbidden("不是该会话成员");
        }
    }

    /** MessageReceived 事件：与消息同事务写入 outbox，notification 模块消费后做订阅提醒。 */
    private void emit(long sessionId, long messageId, long senderId, long receiverId) {
        try {
            outboxRepository.save(new OutboxEvent("chat_message", messageId, "MessageReceived",
                    objectMapper.writeValueAsString(Map.of(
                            "sessionId", sessionId, "messageId", messageId,
                            "senderId", senderId, "receiverId", receiverId))));
        } catch (Exception e) {
            throw new IllegalStateException("领域事件序列化失败", e);
        }
    }

    private static MessageDto toDto(ChatMessage m) {
        return new MessageDto(m.getId(), m.getSessionId(), m.getSenderId(),
                m.getClientMsgId(), m.getType(), m.getContent(), m.getCreatedAt());
    }

    // ---------- RFC-009 管理端流量统计（只追加，不动既有方法；admin StatsController 经此调用，不碰本模块 repository） ----------

    /** 会话总数：MySQL 为事实源，直接 count 表（Redis 里的未读/在线状态不参与统计）。 */
    @Transactional(readOnly = true)
    public long countSessions() {
        return sessionRepository.count();
    }

    @Transactional(readOnly = true)
    public long countMessages() {
        return messageRepository.count();
    }

    /** "今日新增消息"计数，时间边界由调用方统一用 UTC 计算。 */
    @Transactional(readOnly = true)
    public long countMessagesCreatedAfter(OffsetDateTime time) {
        return messageRepository.countByCreatedAtAfter(time);
    }

    /** 逐日统计：[from, to) 半开区间内创建的消息数（from 含、to 不含，避免毫秒边界重复/遗漏）。 */
    @Transactional(readOnly = true)
    public long countMessagesCreatedBetween(OffsetDateTime from, OffsetDateTime to) {
        return messageRepository.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(from, to);
    }
}
