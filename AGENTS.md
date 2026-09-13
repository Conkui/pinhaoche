# AGENTS.md — 拼好车（Pin Hao Che）工作区规范

面向偏僻学校学生的拼车微信小程序。当前已进入 V1 实现阶段（`CONTRACT_FREEZE_V1` 已冻结，见 `docs/CONTRACT_FREEZE_V1.md`）：后端 `server/`（Spring Boot 3 / Java 21 模块化单体）+ 小程序 `miniprogram/`。完整产品/工程规范见根目录《拼好车小程序 AI 实现规范.md》。

## 用户追加的产品红线（2026-09-13 指令，优先级最高）

1. **预打车时间效益**：已过出发时间的预打车单绝不能再展示给后来者。列表接口必须按 `depart_time` 过滤，且定时任务把过期单转为 `EXPIRED`（状态机 PUBLISHED/MATCHING→EXPIRED 合法）。"今天打开不能看到昨天的拼车单"。
2. **已打上导入**：滴滴/花小猪无官方第三方 API（禁止抓包/破解）。真实订单读取接口留白（`OcrProvider` / `RideProviderAdapter` 已定义、实现标记 Not Completed），V1 降级链路：分享文本解析预填 → 用户确认 → 手动填写。
3. **意见反馈**：入口放"我的"页（不与主功能抢位置），后端 `POST /api/v1/feedback`（RFC-006，V2 迁移）。
4. **验收先行**：每次交付必须跑 `docs/ACCEPTANCE_V1.md` 的模拟调用清单；小程序必须保证微信开发者工具导入即可运行。
5. 会话中断由每小时的自动任务恢复执行（先读 PROJECT_STATUS.md 续做，禁止重复已完成工作）。

## 开始任何任务前

1. 先读 [PROJECT_STATUS.md](PROJECT_STATUS.md)（当前版本、已完成/未完成模块、下一阶段）。
2. 涉及的契约先读 `docs/` 下的冻结文档：[ARCHITECTURE.md](docs/ARCHITECTURE.md)、[STATE_MACHINE.md](docs/STATE_MACHINE.md)、[WEBSOCKET_PROTOCOL.md](docs/WEBSOCKET_PROTOCOL.md)、[PRODUCT_SPEC.md](docs/PRODUCT_SPEC.md)、[UI_SPEC.md](docs/UI_SPEC.md)。
3. 事实来源优先级：**源码 > openapi.yaml > db/migration > docs/ > PROJECT_STATUS.md > Agent 对话描述**。Agent 说"已实现"但代码库不存在 = 未实现。不要把聊天上下文当事实来源。

## 契约冻结规则（最重要）

冻结后，**任何 Agent 不得直接修改**：`openapi.yaml`、`db/migration/V1__init.sql`、Ride 状态/状态机、WebSocket 协议、公共 DTO。发现设计问题 → 在 `docs/rfc/` 新建 `RFC-XXX.md`（问题、原因、影响模块、建议），由 Integration Agent 决定是否接受。禁止为方便自己代码而偷偷改公共结构。

## 技术栈与架构边界

- 小程序：微信原生 + TypeScript 严格模式 + WeUI。后端：Spring Boot 3.x / Java 21 模块化单体（不拆微服务）+ MySQL 8 + Redis 7 + RabbitMQ + Flyway + Nginx/Docker。
- 模块：`auth`、`user`、`place`、`ride`、`chat`、`notification`、`importer`、`admin`、`common`、`infrastructure`。每个模块只暴露 application service 与 DTO；**禁止跨模块直接操作对方 repository/table**。
- MySQL 是业务事实源；Redis 只做缓存、在线状态、未读数、连接路由、通知冷却；RabbitMQ 只消费 Outbox 外围事件（通知、缓存失效、日志、OCR 异步）。核心业务事务内同时写业务数据 + `outbox_event`，消费者必须可重复消费。
- 外部依赖（滴滴/花小猪、OCR、微信订阅消息）必须先定义 interface（如 `RideProviderAdapter`、`OcrProvider`、`NotificationGateway`），实现 Adapter；**禁止虚构第三方接口**。无合规 Adapter 时降级为"需手动填写"。

## 硬性产品/技术约束

- V1 **不使用地图、GPS、模糊地点搜索**。地点仅两种：管理员预设（`PRESET`）与用户自定义（`CUSTOM`，仅属于当前 Ride）。
- Ride 即使使用 PRESET 地点，也必须把名称快照写入 `origin_text` / `destination_text`。
- 状态机：`DRAFT→PUBLISHED→MATCHING→BOOKED→IN_PROGRESS→ARRIVED→CLOSED`，异常 `CANCELLED`/`EXPIRED`。迁移只经 `RideStateMachine` 集中校验；BOOKED = 已叫到车但车主仍愿等人。
- 并发人数：禁止"SELECT→Java 判断→UPDATE"，必须用带 `version` 和 `member_count < capacity` 条件的条件 UPDATE（见规范 §20）。
- 幂等：写操作带 `Idempotency-Key`（Redis SETNX 降低压力 + 数据库唯一约束兜底）；聊天消息按 `(sender_id, client_msg_id)` 唯一索引去重。
- WebSocket（`/ws/v1/chat`）顺序固定：校验成员 → 落库 → ACK 发送者 → 推送对方。禁止先推送后保存。断线用 `lastMessageId` 经 HTTP 补偿。聊天记录保存 30 天定时清理。
- 通知防骚扰：微信订阅消息 5 分钟冷却（Redis `notify:{sessionId}:{receiverId}`），期间只累加未读；接收者在线则只走 WebSocket 不发订阅消息。
- 认证身份只从 token 解析，不接受客户端传入 ownerId/senderId。车牌在日志中脱敏。数据库变更只能通过 Flyway。

## 代码与 UI 规范

- 关键实现必须有**中文注释**，解释为什么、解决什么问题、何时失败、如何降级；禁止无意义的复述式注释。重点覆盖：并发、幂等、MQ/Outbox、状态机、WebSocket、订阅消息、Adapter、OCR 降级、权限。
- 小程序领域逻辑写为纯函数（readonly interface 输入输出），副作用只存在于 adapter/service 边界；禁止 `any` 当领域模型。Java 侧优先不可变 record/DTO。
- UI 必须遵循 Apple HIG + WeUI：白卡片、浅灰背景、统一圆角、系统蓝主色、每页一个 Primary Action；禁止大面积渐变、霓虹色、复杂阴影、花哨动画。**Agent 不得自由发挥设计**。
- 禁止 TODO 冒充完成、Mock 冒充生产逻辑；核心业务必须有自动化测试。

## 开发流程规则

- 短任务 Agent + 固定契约 + 独立 Review：每个 Agent 只负责一个模块边界（如 Place Agent 不碰 Ride，Chat Agent 不实现订阅通知），输入只含该模块需求/相关 OpenAPI/相关表，不要整包文档。
- 每个 Agent 开始前读 PROJECT_STATUS.md，完成后只更新自己负责的部分。交付必须含：源码、测试、README、变更说明、明确列出 Completed / Not Completed / Known Issues。
- 开发 Agent 不得给自己做最终验收；由独立 Review/QA Agent 检查。Git：每个 Agent 独立分支（`feature/place` 等），禁止多人直接改 main。
- V1 核心验收链路：登录 → 选地点 → 发布预打车 → 联系聊天 → 叫车 → 导入"已打上" → 用户 C 联系 → 微信提醒 → 加入 → 车主"不再等人"下架。此链路优先级高于一切附加功能。

## 构建/测试命令

当前仓库无任何可执行代码与构建配置，无 build/lint/test 命令。后续后端建立后补充：Maven 构建与 Flyway 迁移命令；小程序侧补充微信开发者工具 CI 说明。
