# RFC-016：用户端前端从小程序迁移为手机端 Web 网页版

## 问题

微信小程序版本未通过平台审核，用户端没有可用的触达渠道。用户 2026-09-16 直接指令：**保留并提交当前小程序版本（标记 commit「微信小程序over，web start」），将前端转换为适应手机端的 Web 网页版**。

## 决策与边界

1. `miniprogram/` 目录**冻结保留、不再演进**（已完成功能作为 Web 版的实现参照与回退方案），不做删除。
2. 新增 Web 前端，**后端 Java 代码、openapi 契约、Flyway、状态机、WS 协议零改动**。本 RFC 只新增前端形态，不触碰任何冻结契约。
3. Web 版消费与小程序完全相同的 `/api/v1` REST + `/ws/v1/chat` WebSocket 协议。

## 技术方案

### 托管与访问（同源，零 CORS）

- Web 前端为**无构建步骤**的原生 ES Module SPA（vanilla JS + JSDoc 类型标注），放置于 `server/src/main/resources/static/`，由 Spring Boot 与既有 `/admin/index.html` 同机制直接托管。
- 访问入口 `http://<host>:8080/`（static 根 `index.html` 即欢迎页）。前端与 API **同源**，不存在 CORS 问题，无需任何后端改动或 Nginx 特殊配置。
- 选择无构建方案的理由：与「微信开发者工具导入即可运行」同等的生产力目标——`docker compose up -d --build` 后即可访问，不需要 Node 构建链进入部署路径；域逻辑仍以纯函数 + JSDoc 类型约束（对应原 TS 严格模式精神）。

### 登录（V1 Web 的硬边界，Not Completed 留白）

- Web 页面**无法调用 `wx.login`**，因此无法走真实 `jscode2session` 链路。V1 Web 仅支持 `WECHAT_MOCK=true`：客户端在 localStorage 持久化固定 dev code，后端 mock 网关把 code 当 openid，身份稳定。
- **Web 上线体验期间 `.env` 必须置 `WECHAT_MOCK=true`**（与小程序时代「两边必须同时切」不同，Web 只有 mock 一条路）。
- 面向真实用户的 Web 登录（公众号网页授权 / 开放平台扫码 / 短信验证码）**留白**：需要用户侧凭证（认证公众号或开放平台应用），留待独立 RFC 评审后实施，不在本批次虚构第三方接口。

### 通知降级

微信订阅消息是小程序专属能力。Web 场景下离线用户不再有订阅消息触达，降级为「WS 在线实时推送 + 未读数/列表红点」——后端既有逻辑（在线只走 WS、离线累加未读）本就兼容该形态，无需改动。

## 页面映射（11 → 10 页）

| 小程序页面 | Web 路由 | 说明 |
| --- | --- | --- |
| pages/booked | `#/booked`（tab 实时拼） | 实时拼列表 + 可用性开关/公告 |
| pages/prebook | `#/prebook`（tab 找拼友） | 找拼友列表 + 路线筛选 + 发布入口 |
| pages/messages | `#/messages`（tab 消息） | 会话列表 |
| pages/profile | `#/profile`（tab 我的） | 资料/我的发布/反馈入口 |
| pages/publish | `#/publish` | 发布预打车（内联地点下拉 + 原生 date/time） |
| pages/import | `#/import` | 导入已打上（分享文本解析预填 → 确认） |
| pages/ride-detail | `#/ride/:rideId` | 详情 + 加入/联系车主/状态迁移 |
| pages/my-rides | `#/my-rides` | 我的发布 + 下架（二次确认 + 幂等键复用） |
| pages/chat | `#/chat/:sessionId` | 聊天（WS 三态发送/断线补偿/删本人消息） |
| pages/feedback | `#/feedback` | 意见反馈（RFC-006） |
| pages/place-picker | （不迁移） | 孤儿页，顺带落实遗留 P2 的摘除决定 |

## UI 基线

沿用 RFC-007 设计令牌：主色 `#1677ff`、主按钮 4% 同色系轻渐变 + 柔和投影、白卡片圆角浅投影、功能色体系（成功/警示/危险/信息）、每页一个 Primary Action、卡片视觉顺序不变（路线>联系>余位>车牌>距离）。移动端优先：`viewport-fit=cover`、底部安全区适配、桌面浏览时 480px 居中呈现。

## 实施（多 Agent 边界）

- 集成者：骨架（index.html / app.css 设计系统 / config / format / auth / request / ui / app 路由壳）+ 修复 + 构建 + 浏览器 E2E + 合并提交。
- Ride 域 Agent（互不重叠）：`js/services/{ride,place,feature}.js`、`js/utils/shareText.js`、`js/pages/{booked,prebook,publish,import,ride-detail,my-rides}.js`、`css/pages/ride.css`。
- Chat/User 域 Agent：`js/services/{chat,ws,user,feedback}.js`、`js/pages/{messages,chat,profile,feedback}.js`、`css/pages/chat.css`。
- 独立 Review Agent（只读）按清单审查：越权改动 / 契约一致性（以源码为准，openapi 滞后于 RFC-012~015）/ 令牌一致性 / 发送三态与四态红线 / 代码质量。

## Not Completed（本批次明确留白）

1. 面向真实用户的 Web 登录（需公众号/开放平台/短信等用户侧凭证）。
2. 微信订阅消息（Web 无此能力，按上文降级）。
3. 真实 OCR Provider（不变，沿用小程序时代的降级链路）。
4. 压测（不变，见 PROJECT_STATUS 阻塞原因）。

## 状态

已由用户 2026-09-16 指令直接接受；由集成者搭骨架、双域子 Agent 并行实施、独立 Review Agent 审查。
