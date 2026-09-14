# 项目状态

| 项目 | 当前值 |
| --- | --- |
| 当前版本 | V1.0.0-impl.6（CONTRACT_FREEZE_V1 + RFC-001~011；git 基线 802fc0b） |
| 当前阶段 | **真实上线体验批次（RFC-011）**：mock/real 由 `.env` + 小程序单开关 `MODE` 驱动；真实微信登录链路修复（微信 `jscode2session` 响应头是 `text/plain`，原实现必然失败）；真实微信头像昵称（头像昵称填写能力 + `PUT /users/me` + `/files/**` 落盘与静态读取）；从零启动文档 `docs/RUNBOOK.md`（含后台管理页）。独立 Review 结论见下 |
| 数据库版本 | V7__enforce_chat_session_ride_fk.sql（Flyway；V1 冻结未改。V6 将 V4 历史隐藏指令及其关联数据物理删除并移除软删除表；V7 补会话到订单外键） |
| API 版本 | /api/v1（RFC-001/002/004/006/008/011 端点已实现） |
| 已完成模块 | 契约全部文档；后端 auth/user/place/ride/chat/notification/importer/feedback/admin/common/infrastructure；小程序 10 页面 + 服务层；预打车出发时间过期过滤 + EXPIRED 定时任务；分享文本解析预填；意见反馈；管理端 Web 后台；接口限流与文本净化；**实时拼与找拼友独立开关及公告页**；**管理员单条/批量订单物理删除（级联清理成员、聊天、事件和幂等记录）**；**聊天页长按删除本人消息**；**真实微信登录（jscode2session）+ 真实头像昵称**；docker 一键环境（`.env` 驱动 mock/real） |
| 尚未完成模块（含阻塞原因） | ① **真实 AppSecret（用户侧凭证，代码已就绪）**——真实登录实测已达"调用微信并解析其 errcode"这一步（`errcode=40125 invalid appsecret` 证明链路通），填入 AppSecret 后即可拿到真实 openid；② 真实微信订阅消息网关——需订阅消息模板 id + 用户授权流程；③ 真实 OCR Provider——需腾讯云/兼容 OCR API 密钥；④ 聊天 30 天清理定时任务——纯代码任务，待下个批次；⑤ 小程序订阅消息授权交互（`wx.requestSubscribeMessage`）——依赖②的模板 id；⑥ 压测（HTTP 300 QPS / WS 5000 连接）——需独立压测环境与工具 |
| 已知问题 | 滴滴/花小猪无官方第三方 API（禁止抓包），导入走降级链路；WS 路由单实例实现；**管理后台默认密钥 dev-admin-key 生产必须更换**，建议 HTTPS + IP 白名单；admin 端点暂无自动化测试；**登录限流依赖 Nginx 覆盖式 XFF 配置（RFC-010 部署硬约束）**；**头像存本地盘**（多实例/无卷重建会丢，V2 接对象存储）；**`/files/**` 免鉴权且不受限流覆盖**（`<image src>` 无法带 token，UUID 不可枚举缓解，RFC-011 记录权衡）；**生产 Nginx 需配 `client_max_body_size 8m`**（默认 1m 会让头像上传 413）；畸形 JSON 请求体返回 Spring 默认错误体而非契约 `ApiError`（既有全局缺口）；头像落盘失败返回 400 而非 500（`ErrorCode` 冻结，不允许新增 `INTERNAL_ERROR`）；头像并发保存会让落败方文件成孤儿（`app_user` 无 `@Version`）；mock 身份漂移修复前的旧数据挂在旧 openid 下，清缓存重登即归位 |
| 真实模式开关（新增，替代旧的 DEV_MOCK_LOGIN 手工开关） | 后端：根目录 `.env`（模板 `.env.example`）的 `WECHAT_MOCK` / `WECHAT_APPID` / `WECHAT_SECRET` / `ADMIN_KEY` / `UPLOAD_DIR`；小程序：`miniprogram/config.ts` 的 `MODE: 'mock' \| 'real'`（`SERVER_ORIGIN`/`BASE_URL`/`WS_URL`/`DEV_MOCK_LOGIN` 全部由它派生）。**两边必须同时切**，只切一边登录必然 401；逐步命令见 `docs/RUNBOOK.md` 第 6 节 |
| Review 遗留 P2（安全批次，APPROVE_WITH_FIXES） | P2-1 限流 catch 范围偏大；P2-4 TextSanitizer 单测待补；P2-5 前端 `Promise.finally` 低版本基础库兼容待真机验证；P2-6 NOT_FOUND/UNAUTHORIZED 未入 openapi ApiError 枚举 |
| 最近完整测试 | 2026-09-14（impl.6）：容器 `mvn -B test` **39/39 PASS**（新增 `WxJsCode2SessionGatewayTest` 8 例、`AvatarStorageTest` 10 例、`UserProfileServiceTest` 9 例）；`npx tsc --noEmit` 0 error；`scripts/smoke-profile.sh` **PASS=7 FAIL=0**（含目录穿越断言）；`scripts/e2e.sh` B1–B16 全 PASS；B15 过期单 65s 落 `EXPIRED`；B18 403/200/403；B19 标量齐全 + daily 7 天升序 + 非法 status 400 + ISO 时间；B20 25 连发 = 20×200 + 5×429；B21 静态后台页 200；**真实模式实测**（临时实例 `WECHAT_MOCK=false`）：日志 `微信登录失败 errcode=40125 errmsg=invalid appsecret` + 接口 401 `UNAUTHORIZED`，未配 secret 时启动即出现 AuthConfig 配置告警 |
| 下一阶段 | ① 用户填入真实 AppSecret（`.env`）→ 开发者工具 `MODE='real'` → 按 `docs/ACCEPTANCE_V1.md` E 清单人工确认真实 openid 与真实头像昵称；② 处理 Review 遗留 P2 与 PROJECT_STATUS 末尾遗留项；③ 接真实订阅消息/OCR（需外部凭证）；④ 压测 |

## 本批次（RFC-011 / impl.6）交付摘要

- **契约**：`docs/rfc/RFC-011-user-profile.md` 新增，`openapi.yaml` **纯增量**（新增 `PUT /users/me`、`UserProfile`/`UpdateProfileRequest` schema、`BadRequest` response，既有字段语义零改动），`docs/CONTRACT_FREEZE_V1.md` 补登 RFC-007~011。
- **后端**：`user` 模块新增 `UserProfileController/UserProfileService/AvatarStorage/UploadedFileConfig`；头像安全边界全部在服务端（解码前文本长度拦截 → 严格 base64 → 2MiB → 魔数白名单 JPEG/PNG/GIF/WEBP → 显式拒 SVG → 服务端生成 UUID 文件名 → 删旧文件限目录内）；旧头像清理挂在**事务提交后**（`TransactionSynchronization.afterCommit`），回滚时补偿删除新文件。身份只从 token 解析，请求体无任何用户 id 字段。
- **小程序**：`config.ts` 收敛为 `MODE` 单开关；新增 `services/user.ts`（压缩 → base64 → 发送前体积拦截 → PUT → 回写登录态）；"我的"页落地头像昵称填写能力（`open-type="chooseAvatar"` + `input type="nickname"`，**禁用 `wx.getUserProfile`**——改版后返回匿名数据）。
- **部署/文档**：`docker-compose.yml` 环境变量改为 `.env` 驱动 + 上传目录持久化卷；新增 `.env.example`；新增 `docs/RUNBOOK.md`（从零启动、后台管理页、mock↔real 切换、FAQ、上线硬约束）；`README.md`/`server/README.md`/`miniprogram/README.md`/`docs/UI_API_MAPPING.md`/`docs/ACCEPTANCE_V1.md`（新增 E 清单）同步更新。
- **独立 Review（两轮）**：第一轮 APPROVE_WITH_FIXES → 修复 P1-1（事务时序）与全部可修 P2 项；第二轮只读复核（含仓库外沙箱独立复跑 39/39）确认 11 条修复全部生效，并额外发现两处**真实登录链路**风险，已一并修复：
  1. **P0（集成实测发现）**：`WxJsCode2SessionGateway` 把微信响应直接映射 DTO，而微信 `jscode2session` 响应头是 `Content-Type: text/plain`，RestTemplate 默认 Jackson 转换器只认 `application/json` → **即使 AppSecret 正确，真实登录也永远 401**。已改为取原始字节 + 显式 JSON 解析。
  2. **P1（Review 第二轮发现）**：全应用只有 `InfrastructureConfig` 里裸 `new ObjectMapper()` 的 `@Primary` mapper（`FAIL_ON_UNKNOWN_PROPERTIES` 默认开启），而微信对第三方响应**只增不减** → 一旦多返回一个字段，所有真实登录瞬间全量 401 且日志无线索。已加 `@JsonIgnoreProperties(ignoreUnknown = true)`。
  3. **P2**：UTF-8 中文 `errmsg` 因 `text/plain` 不带 charset 被按默认字符集解码成乱码（真实模式排障最需要的那句提示不可读）→ 改为 `byte[].class` 接收；`STATUS_UNKNOWN` 不再当作回滚而删新头像文件（只在 `STATUS_ROLLED_BACK` 时删）；小程序头像读取失败改用 `AvatarReadError` 类型判定而非比较文案；穿越探针改打真实存在的 `/files/../../app.jar`。
  以上均补了回归测试（`WxJsCode2SessionGatewayTest` 8 例，含"微信新增未知字段仍可登录"与"中文 errmsg 日志可读"两条）。

## 环境与端口

`docker compose up -d --build`（配置从根目录 `.env` 读，模板 `.env.example`）：MySQL 宿主 **23306** / Redis **16379** / RabbitMQ **25672+15672** / 后端 **8080**（宿主 3306/5672/6379 被占用或落在 Windows 排除段）。头像落盘到宿主机 `./data/uploads`（已 gitignore）。管理后台 `http://127.0.0.1:8080/admin/index.html`。完整命令见 `docs/RUNBOOK.md`。

## 变更规则

冻结后，任何 Agent 不得直接修改 `openapi.yaml`、`db/migration/V1__init.sql`、Ride 状态或 WebSocket 协议。发现问题时在 `docs/rfc/` 新建 RFC（已接受：RFC-001~011），由集成负责人决定是否接受。

## 遗留 P2（Review 记录，下一批次处理）

- place-picker 页已无入口（孤儿页），决定去留并从 app.json 摘除；
- IdempotencyService 两个理论边界（Error 不补偿、记录落库阶段故障键卡住）可加固；
- `GlobalExceptionHandler` 未处理 `HttpMessageNotReadableException`（畸形 JSON 返回 Spring 默认错误体；验收 E5 的载荷必须用完整 JSON，否则会误判）；
- `/files/**` 未加 `X-Content-Type-Options: nosniff`（纵深加固，现代浏览器风险低）；
- 头像并发保存的孤儿文件（可用 `@Version` 乐观锁或引用计数清理）；
- `AvatarStorage` 落盘失败的 400/500 语义（受"不新增错误码"约束，下个 RFC 再议）；
- RFC-010 限流 catch 范围偏大；`TextSanitizer` 单测待补；前端 `Promise.finally` 低版本基础库兼容待真机验证；`NOT_FOUND`/`UNAUTHORIZED` 未入 openapi `ApiError` 枚举；
- 压测与真实订阅消息/OCR 依赖外部资源（见上表阻塞原因）。
