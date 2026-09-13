package com.pinhaoche.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findBySessionIdAndIdGreaterThanOrderByIdAsc(long sessionId, long afterId, Pageable pageable);

    Optional<ChatMessage> findFirstBySessionIdOrderByIdDesc(long sessionId);

    Optional<ChatMessage> findBySenderIdAndClientMsgId(long senderId, String clientMsgId);

    long countBySessionIdAndIdGreaterThanAndSenderIdNot(long sessionId, long afterId, long senderId);
}
