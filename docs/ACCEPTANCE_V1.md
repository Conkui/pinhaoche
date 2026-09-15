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
| B18 | 管理端鉴权 | 无 `X-Admin-Key` 调 admin 接口 | 403；带密钥 200（RFC-009） |
| B19 | 管理端接口 | `stats/overview`、`feedback` 分页、`rides?status=`、非法 status | 字段完整（12 标量+daily 7 天升序）；非法 status 400；时间字段为 ISO 字符串 |
| B20 | 登录限流 | 同 IP 连续 25 次 `POST /auth/login` | 恰好前 20 次 200，其后 429 RATE_LIMITED（RFC-010） |
| B21 | 静态后台页 | `GET /admin/index.html` | 200；输入密钥后总览/反馈/拼车/地点四 Tab 正常出数据 |
| B22 | 预打车日期范围 | 分别提交上海自然日昨天、今天+1月+1天的 `POST /rides` | 两次均 400 VALIDATION_ERROR；今天与今天+1自然月可提交 |
| B23 | 待同行发布上限 | 同一用户连续发布 4 条仍为 PUBLISHED/MATCHING 的预打车单 | 前 3 条成功；第 4 条 400，消息为“当前委托较多，请完成或下架后再发布”；下架/完成一条后可再发布 |
| B24 | 加入拼车上限 | 同一用户已作为成员加入 3 条非终态 Ride，再加入第 4 条 | 第 4 次 400 VALIDATION_ERROR；CLOSED/CANCELLED/EXPIRED 的成员 Ride 不计入上限 |
| B25 | 新建会话日限额 | 同一用户向 10 位不同车主建会话，再建第 11 个；重复请求任一既有会话 | 前 10 个新建成功；第 11 个 429 RATE_LIMITED；复用既有会话始终 200 且不消耗配额；下一个上海自然日重置 |

## C. 微信开发者工具检查（人工，导入即运行）

1. 导入 `miniprogram/`，勾选"不校验合法域名"，后端 `docker compose up` 运行中。
2. 编译无报错；四个 Tab 可切换；`GET /places` 正常返回。
3. 发布预打车全流程可走通（地点选择 → picker 时间 → 发布 → 列表出现）。
4. 聊天页连接 WS 后显示"已送达"；断网 3 秒恢复后自动补偿消息。
5. 控制台无未捕获异常；无 any 报错（TS 严格模式已开）。
6. 我的页可点头像选图 + 填昵称并保存，保存后头像与昵称立即生效。

## D. 上线前红线复查

- [ ] 身份仅来自 token（后端无任何接受客户端 ownerId/senderId 的参数）
- [ ] 车牌日志脱敏（当前未打车牌日志，接入日志时必须脱敏）
- [ ] 所有写接口幂等键生效（B4/B9）
- [ ] 数据库仅 Flyway 变更（V1 冻结 + V2 feedback）
- [ ] 无 TODO 冒充完成：Not Completed 清单与代码内标记一致（见 server/README.md）

## E. 真实微信模式清单（RFC-011 / 上线前人工确认）

> 前置：`.env` 处于 B 段（`WECHAT_MOCK=false` + 真实 `WECHAT_APPID`/`WECHAT_SECRET`），
> 小程序 `config.ts` 的 `MODE = 'real'`，后端已 `docker compose up -d --build server`。
> 操作步骤见 `docs/RUNBOOK.md` 第 6 节。

| # | 项 | 操作/命令 | 通过标准 |
| --- | --- | --- | --- |
| E1 | `.env` 切 real 后配置真正生效 | 改 `.env` → `docker compose up -d --build server` → `docker compose exec server printenv WECHAT_MOCK` | 输出 `false`；日志无微信配置告警，Flyway 未重复执行（数据不丢） |
| E2 | 工具内登录拿到真实 openid | 开发者工具"我的"页点「重新登录」，随后 `docker compose exec mysql mysql -uroot -proot --default-character-set=utf8mb4 pinhaoche -e "select id,openid,nickname from app_user order by id desc limit 5"` | 登录 200；最新一行 `openid` **不含 `mock_` 前缀**（真实 openid 形如 `o` 开头的 28 位串），且不是 dev uuid |
| E3 | 头像昵称选择与保存 | "我的"页点「重新登录」→ 点头像选真实照片 → 昵称输入框填真实昵称 → 点「保存资料」 | `PUT /users/me` 200；再次查库该行 `nickname` 为所填真实昵称，`avatar` 非空 |
| E4 | 头像返回 `/files/**` 且能加载 | 查库取 `avatar` 值（形如 `/files/<uuid>.png`）→ 浏览器打开 `http://127.0.0.1:8080<avatar>` | 接口 200 且返回真实图片；"我的"页头像同步显示为新图（非首字符兜底色块） |
| E5 | 拒绝 SVG / 超 2MiB | `PUT /users/me` 带 SVG 的 base64；带 >2MiB 的 base64（**用完整合法的 JSON 承载载荷**——截断/畸形 JSON 会走 Spring 默认错误体而非契约 `ApiError`，属既有全局缺口，见 PROJECT_STATUS 遗留项） | 两次均 400 `VALIDATION_ERROR`，服务端未落盘任何文件 |
| E6 | 不存在的文件 404 | `GET http://127.0.0.1:8080/files/not-exist.png` | 404（不是 200/500，也不返回目录内容） |
| E7 | mock↔real 切换不残留身份混淆 | mock 模式登录记下 userId → 切 real 重新登录 → 对比"我的"页 ID 与 `GET /rides/mine` | 两模式为**不同**用户（real 不继承 mock 的发布单）；切回 mock 后仍是原 mock userId，无新增 `mock_` 与真实 openid 的映射混淆记录 |

接口侧可自动化部分已覆盖在 `scripts/smoke-profile.sh`（资料更新、头像落盘与读取、SVG/超限/空体拒绝、404）。

## 最近一次执行结果

| 日期 | A1 | A2 | B 清单 | 结论 |
| --- | --- | --- | --- | --- |
| 2026-09-13 | PASS（7/7 单测，BUILD SUCCESS） | PASS（0 error） | B1-B17 全部 PASS（B9/B17 用 node ws 客户端模拟；B15 过期单 60s 内落 EXPIRED） | 通过 |
| 2026-09-14（impl.4） | PASS（12/12 单测，含 RateLimitWindowTest） | PASS | B1-B17 回归 PASS；B18-B21 新增项 PASS（admin 403/200、ISO 时间、限流恰 20 次/分钟后 429、后台四 Tab 实测截图） | 通过 |
| 2026-09-14（Review 修复后回归） | PASS（12/12） | PASS | B1-B17 全 PASS（12 PASS / 0 FAIL）；限流 25 连发=20×200+5×429 复验；反馈净化链路：纯控制字符被 @NotBlank(trim 语义) 拦截为 400 VALIDATION_ERROR（净化兜底为纵深防御），正常内容 204 | 通过（独立 Review APPROVE_WITH_FIXES：P1-1 部署约束已写入 RFC-010，P2-3 已修复，P2-1/2/4/5/6 记录于 PROJECT_STATUS 遗留项） |
| 2026-09-14（impl.6，RFC-011 真实模式与真实微信资料） | PASS（39/39 单测，BUILD SUCCESS） | PASS（0 error） | B1-B16 全 PASS（`bash scripts/e2e.sh`）；B15 过期单 65s 后 `status=EXPIRED`；B18 无key=403/正确key=200/错误key=403；B19 12 标量齐全 + daily 7 天升序 + 非法 status 400 + `createdAt` 为 ISO 字符串；B20 25 连发=20×200+5×429；B21 静态后台页 200 | 通过。新增 `scripts/smoke-profile.sh` PASS=7 FAIL=0（昵称/头像落盘/`/files/**` 读取/空体·SVG·超限拒绝/不存在 404 + 目录穿越不可读）；**E 清单接口侧已自动化，E2/E3 需真实 AppSecret 人工确认**（真实模式接线已实测：临时实例 `WECHAT_MOCK=false` 时日志出现 `微信登录失败 errcode=40125 errmsg=invalid appsecret` 且接口返回 401 `UNAUTHORIZED`，证明网关确实调用微信并解析其响应；未配 secret 时启动出现 AuthConfig 配置告警）。两轮独立 Review：第二轮 APPROVE_WITH_FIXES（阻塞项为下方缺陷 11，已修复并补测试） |

### 执行中发现并修复的缺陷（验收流程价值记录）

1. 登录响应结构与 openapi 不符（`{token,userId,...}` → `{token,user:{id,nickname,avatar}}`）——契约对齐修复。
2. `ride_member` 复合主键被映射为自增 id（`Unknown column 'rm1_0.id'`）——改 `@IdClass`。
3. `@Modifying` 批量更新后一级缓存返回旧实体（join 响应显示旧状态）——`clearAutomatically/flushAutomatically=true`。
4. 聊天重复 `clientMsgId` 插入污染 Hibernate 会话（提交时 "don't flush the Session after an exception occurs"）——插入隔离到 `TransactionTemplate` 独立事务，重复时新事务读回既有消息。
5. **高并发抢位重试死循环**（压测发现）：MySQL 默认 REPEATABLE READ 下，抢位失败的事务重试时读到的仍是本事务快照的旧 `version`，重试注定失败（实测 50 并发抢 7 座只成功 5 个）——join 事务改为 `READ_COMMITTED` 隔离级 + 重试 8 次，修复后恰好 7 成功、0 超卖。该问题与规范 §20 的条件更新组合使用时必须注意。
6. 环境类（非代码）：宿主机 3306/5672/6379 等端口被占用或落在 Windows 排除段 → compose 端口映射 23306/25672/16379；Git Bash 向 curl 传中文 argv 乱码 → 测试脚本统一 `--data-binary @file`。
7. **自定义 ObjectMapper 覆盖 Boot 默认导致时间序列化为数字**（impl.4 发现）：`@Primary ObjectMapper` 使 Boot 的 `write-dates-as-timestamps=false` 失效，OffsetDateTime 输出 epoch 数字，前端会显示 1970 年——显式 `disable(WRITE_DATES_AS_TIMESTAMPS)` + UTC 时区修复。凡自定义 Jackson Bean 必须检查该开关。
8. **幂等键失败卡死重试**（impl.3 发现）：业务抛异常后 Redis SETNX 键残留，客户端同 key 重试被误判 `IDEMPOTENCY_CONFLICT`——业务失败时补偿删除 Redis 键。
9. **真实微信登录必然失败（impl.6 发现，P0）**：`WxJsCode2SessionGateway` 把微信响应直接映射成 DTO，而微信 `jscode2session` 的响应头是 `Content-Type: text/plain`，RestTemplate 默认 Jackson 转换器只处理 `application/json` → 抛
   `no suitable HttpMessageConverter found for response type [...] and content type [text/plain]`，被 catch 统一转成 401。
   **后果：即使 AppSecret 完全正确，真实登录也永远失败**；mock 模式不走该类，既有单测只覆盖 mock 网关，所以本地联调与 A/B 清单全绿都发现不了，只有真实模式实测才暴露。
   修复：改为 `getForObject(url, String.class)` 取原始文本 + 显式 `ObjectMapper.readValue`，使结果与响应头无关；
   补 `WxJsCode2SessionGatewayTest`（6 例，用 `MediaType.TEXT_PLAIN` 声明响应头把该缺陷钉死），并对日志里的 secret/js_code 脱敏。
   **教训**：接第三方接口的"错误分支"必须用对方真实响应形态（含 Content-Type）做回归测试，不能靠自造 JSON 断言。
10. **删旧头像早于事务提交（impl.6 Review 第一轮 P1-1 发现）**：`@Transactional` 方法内 `saveAndFlush` 只是 flush、commit 发生在方法返回后，
    在方法体内 `deleteIfManaged(oldAvatar)` 会留下"提交失败回滚 → 库里仍指向旧路径、文件已被删"的窗口（头像永久 404）。
    修复：旧文件清理挂到 `TransactionSynchronization.afterCommit`，回滚时补偿删除本次新文件（`STATUS_UNKNOWN` 不动，避免误删可能已被提交引用的新文件）。
    **教训**：事务内做不可回滚的外部副作用（文件、消息、缓存）必须挂 afterCommit。
11. **严格 ObjectMapper + 微信响应对第三方"只增不减"= 所有真实登录全量 401（impl.6 Review 第二轮 P1 发现）**：
    全应用只有 `InfrastructureConfig` 里裸 `new ObjectMapper()` 的 `@Primary` mapper（`FAIL_ON_UNKNOWN_PROPERTIES` 默认开启），
    本次修复第一次让它解析**第三方**响应；微信一旦多返回一个字段（如历史上的 `unionid`、将来的风控字段），
    就会抛 `UnrecognizedPropertyException` → 转 401 → **所有真实登录瞬间失败**，而日志只有"响应无法解析（length=N）"、无线索。
    修复：DTO 加 `@JsonIgnoreProperties(ignoreUnknown = true)` + 回归测试"含未知字段仍可登录"；顺带改为 `byte[]` 接收，
    解决 `text/plain` 不带 charset 导致中文 `errmsg` 在日志里乱码（真实模式排障最需要的那句提示）。
    **教训**：解析外部/第三方响应必须显式声明"容忍未知字段"，且不能用字符串中转（字符集不可控）。
