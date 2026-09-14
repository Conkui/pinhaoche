// Chat 会话 HTTP 服务：建会话 / 会话列表 / 断线补偿 / 已读。
// 实时收发走 ws.ts（WebSocket），这里只承担 REST 部分（docs/WEBSOCKET_PROTOCOL.md）。
import { request } from './request'

export interface ChatSession {
  id: number
  rideId: number
  peerUserId: number
  unreadCount: number
}

export interface ChatSessionSummary {
  id: number
  rideId: number
  peerUserId: number
  unreadCount: number
  lastMessagePreview?: string | null
  lastMessageAt?: string | null
}

export interface Message {
  id: number
  sessionId: number
  senderId: number
  clientMsgId: string
  type: 'TEXT'
  content: string
  createdAt: string
}

export function ensureSession(rideId: number, peerUserId: number): Promise<ChatSession> {
  return request<ChatSession>('/chat-sessions', {
    method: 'POST',
    data: { rideId, peerUserId },
  })
}

export function listSessions(): Promise<ChatSessionSummary[]> {
  return request<ChatSessionSummary[]>('/chat-sessions')
}

/** 断线重连补偿：afterId 传本地最大 messageId。 */
export function fetchMessages(sessionId: number, afterId: number): Promise<Message[]> {
  return request<Message[]>(`/chat-sessions/${sessionId}/messages?afterId=${afterId}`)
}

export function markRead(sessionId: number): Promise<void> {
  return request<void>(`/chat-sessions/${sessionId}/read`, { method: 'POST' })
}

/** 只允许删除本人已持久化的消息；发送中/失败消息没有服务端 messageId，不走此接口。 */
export function deleteMessage(sessionId: number, messageId: number): Promise<void> {
  return request<void>(`/chat-sessions/${sessionId}/messages/${messageId}`, { method: 'DELETE' })
}
