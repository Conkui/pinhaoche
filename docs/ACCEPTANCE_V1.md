# V1 验收标准与模拟调用清单（ACCEPTANCE_V1）

> 每次交付必须执行本清单并记录结果。来源：规范 §39 验收链路 + 用户追加要求（时间效益/反馈/导入降级）。
> 环境启动：根目录 `docker compose up --build`（MySQL+Redis+RabbitMQ+后端）。

## A. 自动化测试门槛

| # | 项 | 命令 | 通过标准 |
| --- | --- | --- | --- |
| A1 | 后端编译+单测 | 容器 `mvn -B test` | BUILD SUCCESS，0 Failures 0 Errors |
| A2 | 小程序 TS 严格检查 | `cd miniprogram && npx tsc --noEmit` | 0 error |
| A3 | Flyway 迁移 | 服务启动日志 | `Successfully applied` V1/V2，无 schema 校验错误 |

## B. 端到端模拟调用（curl /api/v1，token 为登录返回）

| # | 步骤 | 调用 | 通过标准 |
| --- | --- | --- | --- |
| B1 | 登录（mock） | `POST /auth/login {"code":"userA"}` ×3 人 | 200，返回不同 userId 与 token |
| B2 | 地点列表 | `GET /places` | 200，管理员预设列表 |
| B3 | A 发布预打车 | `POST /rides`（带 Idempotency-Key） | 201，status=PUBLISHED，origin/destination 快照 text 正确 |
| B4 | 幂等重放 | 同 key 重复 `POST /rides` | 返回同一条 Ride，不新建 |
| B5 | B 加入 | `POST /rides/{id}/join` | 200，memberCount+1，PUBLISHED→MATCHING |
| B6 | 重复加入 | B 再 join | 400 VALIDATION（已加入） |
| B7 | 并发抢最后一位 | 多人同时 join capacity=2 | 恰好 1 人成功，其余 409 CAPACITY_FULL |
| B8 | B 联系 A | `POST /chat-sessions {rideId, peerUserId}` | 200；重复调用返回同一 sessionId |
| B9 | 聊天落库+ACK | WS `SEND_MESSAGE` | 收到 ACK（messageId 与落库一致）；重复 clientMsgId 返回同一消息 ACK |
| B10 | 断线补偿 | `GET /chat-sessions/{id}/messages?afterId=` | 返回增量且有序 |
| B11 | A 叫车导入 | `POST /rides/import`（用户确认字段） | 201，status=BOOKED，出现在 view=BOOKED 列表 |
| B12 | 非法迁移 | `POST /rides/{id}/transitions` PUBLISHED→ARRIVED | 409 ILLEGAL_TRANSITION |
| B13 | 越权 | B 尝试迁移 A 的 Ride | 403 FORBIDDEN |
| B14 | 不再等人 | A 迁移 BOOKED→IN_PROGRESS | 200，view=BOOKED 列表中消失 |
| B15 | 预打车时间效益（红线） | 构造 depart_time 已过的单 | 不出现在 view=PREBOOK；60s 内状态转 EXPIRED |
| B16 | 意见反馈 | `POST /feedback {"content":"..."}` | 204；未登录 401 |
| B17 | 通知冷却 | 同会话连发 5 条（接收者离页） | 订阅消息路径最多 1 次触发，未读数持续累加 |

## C. 微信开发者工具检查（人工，导入即运行）

1. 导入 `miniprogram/`，勾选"不校验合法域名"，后端 `docker compose up` 运行中。
2. 编译无报错；四个 Tab 可切换；`GET /places` 正常返回。
3. 发布预打车全流程可走通（地点选择 → picker 时间 → 发布 → 列表出现）。
4. 聊天页连接 WS 后显示"已送达"；断网 3 秒恢复后自动补偿消息。
5. 控制台无未捕获异常；无 any 报错（TS 严格模式已开）。

## D. 上线前红线复查

- [ ] 身份仅来自 token（后端无任何接受客户端 ownerId/senderId 的参数）
- [ ] 车牌日志脱敏（当前未打车牌日志，接入日志时必须脱敏）
- [ ] 所有写接口幂等键生效（B4/B9）
- [ ] 数据库仅 Flyway 变更（V1 冻结 + V2 feedback）
- [ ] 无 TODO 冒充完成：Not Completed 清单与代码内标记一致（见 server/README.md）

## 最近一次执行结果

| 日期 | A1 | A2 | B 清单 | 结论 |
| --- | --- | --- | --- | --- |
| 2026-09-13 | PASS（7/7 单测，BUILD SUCCESS） | PASS（0 error） | B1-B17 全部 PASS（B9/B17 用 node ws 客户端模拟；B15 过期单 60s 内落 EXPIRED） | 通过 |

### 执行中发现并修复的缺陷（验收流程价值记录）

1. 登录响应结构与 openapi 不符（`{token,userId,...}` → `{token,user:{id,nickname,avatar}}`）——契约对齐修复。
2. `ride_member` 复合主键被映射为自增 id（`Unknown column 'rm1_0.id'`）——改 `@IdClass`。
3. `@Modifying` 批量更新后一级缓存返回旧实体（join 响应显示旧状态）——`clearAutomatically/flushAutomatically=true`。
4. 聊天重复 `clientMsgId` 插入污染 Hibernate 会话（提交时 "don't flush the Session after an exception occurs"）——插入隔离到 `TransactionTemplate` 独立事务，重复时新事务读回既有消息。
5. 环境类（非代码）：宿主机 3306/5672/6379 等端口被占用或落在 Windows 排除段 → compose 端口映射 23306/25672/16379；Git Bash 向 curl 传中文 argv 乱码 → 测试脚本统一 `--data-binary @file`。
