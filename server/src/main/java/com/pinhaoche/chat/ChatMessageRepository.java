package com.pinhaoche.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findBySessionIdAndIdGreaterThanOrderByIdAsc(long sessionId, long afterId, Pageable pageable);

    Optional<ChatMessage> findFirstBySessionIdOrderByIdDesc(long sessionId);

    Optional<ChatMessage> findBySenderIdAndClientMsgId(long senderId, String clientMsgId);

    long countBySessionIdAndIdGreaterThanAndSenderIdNot(long sessionId, long afterId, long senderId);

    // ---------- RFC-009 管理端只读统计（只加不改） ----------

    /** 某时间点（含）之后创建的消息数，用于"今日新增消息"。 */
    long countByCreatedAtAfter(OffsetDateTime time);

    /** 逐日统计用：[start, end) 半开区间计数（避免毫秒边界重复/遗漏）。 */
    long countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(OffsetDateTime start, OffsetDateTime end);
}
