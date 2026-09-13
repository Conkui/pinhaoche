package com.pinhaoche.chat;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 会话归一化与成员判断的纯逻辑测试。 */
class ChatSessionTest {

    @Test
    void lowHighPairNormalizes() {
        ChatSession session = new ChatSession(1L, 42, 7, OffsetDateTime.now());
        assertThat(session.getUserLowId()).isEqualTo(42);
        assertThat(session.getUserHighId()).isEqualTo(7);
        assertThat(session.hasMember(42)).isTrue();
        assertThat(session.hasMember(7)).isTrue();
        assertThat(session.hasMember(99)).isFalse();
    }

    @Test
    void peerOfReturnsCounterpart() {
        ChatSession session = new ChatSession(1L, 7, 42, OffsetDateTime.now());
        assertThat(session.peerOf(7)).isEqualTo(42);
        assertThat(session.peerOf(42)).isEqualTo(7);
    }
}
