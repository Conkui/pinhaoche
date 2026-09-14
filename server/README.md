# pinhaoche-server

拼好车 V1 后端：Spring Boot 3.3.4 / Java 21 模块化单体。契约见根目录 `openapi.yaml` 与 `docs/CONTRACT_FREEZE_V1.md`。

## 模块（模块间只允许经 application service / DTO 交互，禁止跨模块访问 repository）

| 包 | 职责 |
| --- | --- |
| `common.api` | 错误码 / ApiError / 全局异常（HTTP 状态映射） |
| `common.auth` | token 存取（Redis）、API 鉴权拦截、admin 密钥拦截、登录 IP 限流（RFC-010） |
| `common.idempotency` | HTTP 写操作幂等（Redis SETNX + idempotency_record 唯一键） |
| `auth` / `user` | 微信登录（`WeChatGateway` 接口化，mock/real 可切）、用户资料 `PUT /users/me`、`AvatarStorage`（RFC-011） |
| `place` | 预设地点 + 缓存（写后失效）+ 管理端 CRUD（X-Admin-Key，RFC-005） |
| `ride` | Ride 聚合 / RideStateMachine / 条件更新并发（规范 §20）/ 乐观锁迁移 / `GET /rides/mine`（RFC-008） |
| `chat` | 会话/消息/未读/在线状态 + WebSocket（`/ws/v1/chat`，落库→ACK→推送顺序固定） |
| `notification` | MessageReceived 消费 + 5 分钟冷却（规范 §15）+ NotificationGateway 接口 |
| `importer` | RideProviderAdapter/Factory（滴滴/花小猪降级说明）、OcrProvider 接口 |
| `feedback` | 意见反馈（RFC-006） |
| `admin` | 管理端总览/反馈/拼车/地点接口（RFC-009） |
| `infrastructure.outbox` | Outbox Pattern 投递器（业务事务内写 outbox_event） |

静态资源：`src/main/resources/static/admin/index.html` 是单文件管理后台，由后端直接服务
（`http://127.0.0.1:8080/admin/index.html`）；`GET /files/**` 是头像上传目录的只读映射（RFC-011）。

## 环境变量（全部可经根目录 `.env` 驱动，模板见 `.env.example`）

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | `localhost` / `3306` / `pinhaoche` / `<required>` / `<required>` | MySQL 连接（compose 内 `DB_HOST=mysql`） |
| `REDIS_HOST` / `REDIS_PASSWORD` | `localhost` / `<required>` | cache（compose 内为服务名） |
| `RABBITMQ_HOST` / `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | `localhost` / `guest` / `<required>` | 事件总线（compose 内为服务名） |
| `WECHAT_MOCK` | `true` | **mock＝把登录 code 直接当 openid**（零依赖联调）；`false`＝调微信 `jscode2session` 换真实 openid |
| `WECHAT_APPID` | 空 | 小程序 appid（本项目为 `wxe956226c7e7fa726`） |
| `WECHAT_SECRET` | 空 | 小程序 AppSecret，**真实模式必填**；泄露需立即在公众平台重置 |
| `ADMIN_KEY` | `<required>` | 管理端 `X-Admin-Key` 独立强随机共享密钥，禁止提交 |
| `UPLOAD_DIR` | `./data/uploads` | 头像落盘目录（容器内相对 `/app`，compose 绑定挂载到宿主机 `./data/uploads`） |
| `TOKEN_TTL_HOURS` | `72` | token 有效期（Redis） |

**mock / real 对照**：`WECHAT_MOCK=true` + 小程序 `MODE='mock'`（默认，clone 即可跑）；
`WECHAT_MOCK=false` + 真实 `WECHAT_APPID`/`WECHAT_SECRET` + 小程序 `MODE='real'`（真实登录）。
**两边必须同时切**，否则登录必然 401。逐步命令见 `docs/RUNBOOK.md` 第 6 节。

## 本地运行

```bash
# 根目录：一键起 MySQL/Redis/RabbitMQ/后端（配置从根目录 .env 读，模板 .env.example）
cp .env.example .env
# 先替换 .env 中所有 REPLACE_WITH_RANDOM_* 占位符
docker compose up -d --build

# 仅构建与测试（宿主机无 JDK 21 也可，用容器；Git Bash 下两个参数缺一不可）
cd server
MSYS_NO_PATHCONV=1 docker run --rm \
  -v "$(pwd -W):/build" -v phc-maven-repo:/root/.m2 -w /build \
  maven:3.9-eclipse-temurin-21 mvn -B test
```

- `MSYS_NO_PATHCONV=1` 关闭 Git Bash 的 POSIX→Windows 路径转换（否则 `-w /build` 被改写）；
  `$(pwd -W)` 让挂载路径输出 Windows 原生形式（`/e/...` 形式 Docker 无法挂载）。详见 `docs/RUNBOOK.md` 第 7 节。
- 管理端：请求头 `X-Admin-Key: <.env 中的 ADMIN_KEY>`（HTTPS + IP 白名单，且密钥不提交）。
- 数据库结构只经 Flyway（`src/main/resources/db/migration`，源自 `db/migration/V1__init.sql`，冻结）。

## 新增接口（RFC-011）

- `PUT /api/v1/users/me`：资料幂等替换，请求体 `{ "nickname"?: string, "avatarBase64"?: string }`
  （两者至少一个非空，空对象 → 400 `VALIDATION_ERROR`）；响应 `{ id, nickname, avatar }`。
  **userId 只从 token 解析**，请求体不接受任何用户 id 字段。
  昵称：先 `TextSanitizer.clean` 再判长 1–20，净化后为空 → 400。
  头像：解码后 ≤ 2 MiB，按**魔数**判定仅允许 JPEG/PNG/GIF/WEBP，**显式拒绝 SVG**（防存储型 XSS）；
  文件名由服务端生成 `<uuid>.<ext>`（绝不复用客户端文件名，防路径穿越）。
- `GET /files/**`：头像只读静态映射，落库值为相对路径 `/files/<name>`。

## Completed

- 全部 openapi 端点（含 RFC-001/002/004/006/008 补充端点）+ WebSocket 协议 + 管理端（RFC-005/009）
- 幂等（Redis+DB 双保险）、乐观锁条件更新、Outbox、通知冷却、消息去重；登录 IP 限流（RFC-010）
- 预打车过期过滤 + `EXPIRED` 定时任务；真实头像昵称（RFC-011：落盘 + 静态读取 + 校验）
- 单元测试：RideStateMachine 全迁移表、会话归一化、限流窗口、头像校验、微信网关响应形态（RFC-011 新增 27 个）等，当前 39/39

## Not Completed / Known Issues

- 真实微信订阅消息网关（现 `LogNotificationGateway`，DEV-ONLY 日志实现），需模板 id + 用户授权流程
- 真实 OCR Provider（接口已留，未接腾讯云）；滴滴/花小猪无官方 API（禁止抓包），导入走"用户确认+手动填写"降级
- 聊天 30 天清理定时任务未实现；单实例 WS（多实例需 Redis 路由，代码已注释说明）
- `/files/**` **免鉴权**：小程序 `<image src>` 无法携带 `Authorization` 头，若要求 token 则头像永远 404。
  代价是"知道文件名即可读"，用不可枚举的 UUID 文件名缓解，且头像属用户主动公开信息；生产如需更严可加 Referer/域名校验。
  该路径也**不受 RFC-010 限流覆盖**（限流 patterns 为 `/api/v1/**`），生产必须靠 Nginx 层兜底
- 头像存**本地盘**（`UPLOAD_DIR`）：多实例部署或容器无卷重建会丢文件（库里只剩失效路径），V2 需抽 `FileStorage` 接口接对象存储
- `AvatarStorage` 落盘失败（磁盘满/权限）返回 **400** 而非 500：`ErrorCode` 是冻结契约、不允许新增 `INTERNAL_ERROR`，
  代价是"服务端故障"被标成"客户端参数错误"，排查时需结合 `log.error` 的"头像落盘失败"定位
- 畸形 JSON 请求体（如被截断的 `{`）返回 Spring 默认错误体而非契约里的 `ApiError`：
  `GlobalExceptionHandler` 未处理 `HttpMessageNotReadableException`（属既有全局缺口，非 RFC-011 引入，下批次对齐）
- 头像并发重复保存会让落败一方的新文件成为孤儿（`app_user` 无 `@Version` 乐观锁）；同一用户自己连点触发，低危
- 登录限流依赖 **Nginx 覆盖式 XFF 配置**（RFC-010 部署硬约束，生产落库前必须落实，否则可伪造 XFF 绕过）
- admin 端点暂无自动化测试（下批次补 MockMvc 切片测试）；集成测试（Testcontainers）与压测未做
- 昵称不做唯一性与内容审核（RFC-011 明确，V1 无内容审核能力）
