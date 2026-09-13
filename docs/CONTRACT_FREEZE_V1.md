# CONTRACT_FREEZE_V1

- 冻结版本：V1.0.0（基于 V1.0.0-spec.1）
- 冻结时间：2026-09-13
- 冻结范围：`openapi.yaml`、`db/migration/V1__init.sql`、Ride 状态机（docs/STATE_MACHINE.md）、WebSocket 协议（docs/WEBSOCKET_PROTOCOL.md）、公共 DTO。

## 冻结前修订（已经 Integration 评审接受，随冻结一并生效）

进入实现阶段时发现原契约缺少实现验收链路所必需、但未覆盖的接口。按规范 §28 以 RFC 形式补齐后再冻结：

| RFC | 内容 | 影响 |
| --- | --- | --- |
| [RFC-001](rfc/RFC-001-chat-session-list.md) | 新增 `GET /chat-sessions` 会话列表 | openapi.yaml |
| [RFC-002](rfc/RFC-002-auth-login.md) | 新增 `POST /auth/login` 微信登录换 token | openapi.yaml |
| [RFC-003](rfc/RFC-003-ride-remark.md) | Ride 响应补充 `remark` 字段（预打车列表卡片需要展示备注） | openapi.yaml |
| [RFC-004](rfc/RFC-004-ride-import.md) | 新增 `POST /rides/import`（用户确认后创建"已打上"BOOKED Ride） | openapi.yaml |
| [RFC-005](rfc/RFC-005-admin-places.md) | 管理端地点 CRUD 走 `/api/v1/admin/**`，不属于小程序契约，用 `X-Admin-Key` 保护 | 仅后端内部 |

## 冻结后修订（走 RFC 流程，Integration 评审接受）

| RFC | 内容 | 影响 |
| --- | --- | --- |
| [RFC-006](rfc/RFC-006-feedback.md) | 新增 `POST /feedback` 意见反馈；新增 Flyway `V2__feedback.sql` | openapi 未动（新增端点）、DB V2 迁移 |

数据库结构冻结不变（V1__init.sql 无改动）。冻结后任何修改必须新建 RFC，由 Integration 负责人决定是否接受。

## 冻结后实现规则

- 后端模块：`auth`、`user`、`place`、`ride`、`chat`、`notification`、`importer`、`admin`、`common`、`infrastructure`，模块间只经 application service 与 DTO 交互。
- 数据库结构只能通过 Flyway 迁移修改（V2 起）。
- 每个 Agent 独立分支短任务实现，交付须列出 Completed / Not Completed / Known Issues。
