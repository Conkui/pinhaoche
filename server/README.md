# pinhaoche-server

拼好车 V1 后端：Spring Boot 3.3 / Java 21 模块化单体。契约见根目录 `openapi.yaml` 与 `docs/CONTRACT_FREEZE_V1.md`。

## 模块（模块间只允许经 application service / DTO 交互，禁止跨模块访问 repository）

| 包 | 职责 |
| --- | --- |
| `common.api` | 错误码 / ApiError / 全局异常（HTTP 状态映射） |
| `common.auth` | token 存取（Redis）、API 鉴权拦截、admin 密钥拦截 |
| `common.idempotency` | HTTP 写操作幂等（Redis SETNX + idempotency_record 唯一键） |
| `auth` / `user` | 微信登录（WeChatGateway 接口化，mock 可切）、用户 |
| `place` | 预设地点 + 缓存（写后失效）+ 管理端 CRUD（X-Admin-Key） |
| `ride` | Ride 聚合 / RideStateMachine / 条件更新并发（规范 §20）/ 乐观锁迁移 |
| `chat` | 会话/消息/未读/在线状态 + WebSocket（`/ws/v1/chat`，落库→ACK→推送顺序固定） |
| `notification` | MessageReceived 消费 + 5 分钟冷却（规范 §15）+ NotificationGateway 接口 |
| `importer` | RideProviderAdapter/Factory（滴滴/花小猪降级说明）、OcrProvider 接口 |
| `infrastructure.outbox` | Outbox Pattern 投递器（业务事务内写 outbox_event） |

## 本地运行

```bash
# 根目录：一键起 MySQL/Redis/RabbitMQ/后端
docker compose up --build

# 仅构建与测试（宿主机无 JDK 21 也可，用容器）
docker run --rm -v "%cd%/server:/build" -w /build maven:3.9-eclipse-temurin-21 mvn -B test
```

- 默认 `WECHAT_MOCK=true`：`code` 即 openid，联调无微信依赖；生产置 `false` 并配置 appid/secret。
- 管理端：请求头 `X-Admin-Key: dev-admin-key`（生产必须经环境变量覆盖）。
- 数据库结构只经 Flyway（`src/main/resources/db/migration`，源自 `db/migration/V1__init.sql`，冻结）。

## Completed

- 全部 openapi 端点（含 RFC-001/002/004 补充端点）+ WebSocket 协议 + 管理端地点 CRUD（RFC-005）
- 幂等（Redis+DB 双保险）、乐观锁条件更新、Outbox、通知冷却、消息去重
- 单元测试：RideStateMachine 全迁移表、会话归一化

## Not Completed / Known Issues

- 真实微信订阅消息网关（现 LogNotificationGateway，DEV-ONLY 日志实现）
- 真实 OCR Provider（接口已留，未接腾讯云）；滴滴/花小猪无官方 API，导入走"用户确认+手动填写"降级
- 聊天 30 天清理定时任务未实现；单实例 WS（多实例需 Redis 路由，代码已注释说明）
- 集成测试（Testcontainers）与压测未做
