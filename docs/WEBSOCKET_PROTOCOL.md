# WebSocket 协议 V1

连接地址：`/ws/v1/chat`。鉴权 token 在握手阶段传入；服务端以 token 身份覆盖任何客户端用户字段。

## 客户端发送文本

```json
{"type":"SEND_MESSAGE","clientMsgId":"uuid","sessionId":10001,"content":"我马上到出发点。","timestamp":1730000000000}
```

服务端顺序固定为：校验会话成员 → 以 `(sender_id, client_msg_id)` 去重并落库 → 提交事务 → ACK 发送者 → 推送接收者/创建通知事件。重复 `clientMsgId` 返回同一条已持久化消息的 ACK，不新建记录。

```json
{"type":"ACK","clientMsgId":"uuid","messageId":90001,"persistedAt":"2026-09-13T10:00:00Z"}
```

## 服务端推送与补偿

```json
{"type":"MESSAGE","message":{"id":90001,"sessionId":10001,"senderId":42,"type":"TEXT","content":"我马上到出发点。","createdAt":"2026-09-13T10:00:00Z"}}
```

断线重连后客户端调用 HTTP `GET /api/v1/chat-sessions/{id}/messages?afterId={lastMessageId}` 补偿。读取成功后调用 read 接口清除当前用户未读。协议未知 type、非成员会话、内容超限均返回 `ERROR`，不得静默丢失。

