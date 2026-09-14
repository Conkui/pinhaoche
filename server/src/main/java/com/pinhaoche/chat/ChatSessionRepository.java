package com.pinhaoche.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    Optional<ChatSession> findByRideIdAndUserLowIdAndUserHighId(long rideId, long lowId, long highId);

    List<ChatSession> findByUserLowIdOrUserHighIdOrderByUpdatedAtDesc(long lowId, long highId);

    List<ChatSession> findByRideId(long rideId);
}
