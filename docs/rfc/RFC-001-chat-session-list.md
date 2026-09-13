# RFC-001：新增 GET /chat-sessions 会话列表

## 问题
底栏"消息"页需要展示当前用户全部会话（含未读数），现有契约只有 `POST /chat-sessions`（创建/复用会话），无列表接口，链路无法闭环。

## 为什么需要修改
验收链路第 10 步（双方沟通）与消息页强依赖会话列表。

## 影响模块
chat、小程序消息页。

## 建议修改
新增 `GET /api/v1/chat-sessions` → `ChatSessionSummary[]`：
`{ id, rideId, peerUserId, unreadCount, lastMessagePreview, lastMessageAt }`（后两个可空）。只读，不影响现有 DTO。
