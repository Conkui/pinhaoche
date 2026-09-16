# 拼好车 Web 版（手机端网页）

小程序审核未过后的用户端替代形态（RFC-016）。无构建步骤的原生 ES Module SPA，由 Spring Boot
与 `/admin` 静态页同机制托管：**`docker compose up -d --build` 后直接访问 `http://127.0.0.1:8080/`**，
与后端同源、零 CORS 配置。

## 运行前提

- 后端 `.env` 必须 `WECHAT_MOCK=true`（Web 无法调用 `wx.login`，V1 仅支持 mock 登录，见 RFC-016）。
- 修改前端文件后需 `docker compose up -d --build server` 重建生效（静态资源打进 jar）。
- 前端被单独用静态服务器打开（与后端不同源）时，需在 `index.html` 顶部注入：
  `<script>window.PHC_CONFIG = { serverOrigin: 'http://127.0.0.1:8080' }</script>`。

## 结构与归属（多 Agent 边界，RFC-016）

| 文件 | 归属 | 说明 |
| --- | --- | --- |
| `index.html` / `css/app.css` | 集成者 | 壳 + 设计系统（RFC-007 令牌唯一来源） |
| `js/app.js` | 集成者 | 路由表/头部/底栏/登录编排（页面契约见文件头注释） |
| `js/config.js` `js/ui.js` | 集成者 | 配置、toast/confirm/loading/空态 |
| `js/utils/` `js/services/{auth,request}.js` | 集成者 | 纯函数与 HTTP/会话 |
| `js/services/{ride,place,feature}.js` `js/utils/shareText.js` | Ride 域 Agent | |
| `js/pages/{booked,prebook,publish,import,ride-detail,my-rides}.js` `css/pages/ride.css` | Ride 域 Agent | |
| `js/services/{chat,ws,user,feedback}.js` | Chat/User 域 Agent | |
| `js/pages/{messages,chat,profile,feedback}.js` `css/pages/chat.css` | Chat/User 域 Agent | |

## 页面 ↔ API 映射

| 路由 | 页面 | 主要 API |
| --- | --- | --- |
| `#/booked` | 实时拼列表 | `GET /rides?view=BOOKED`、`GET /features/realtime-carpool` |
| `#/prebook` | 找拼友列表 | `GET /rides?view=PREBOOK`、`GET /features/find-friends`（路线筛选为本地纯函数过滤） |
| `#/publish` | 发布预打车 | `POST /rides`（Idempotency-Key）、`GET /places` |
| `#/import` | 导入已打上 | `POST /rides/import`（分享文本解析仅预填，用户确认后提交） |
| `#/ride/:id` | 拼车详情 | `GET /rides/{id}`、`POST /rides/{id}/join`、`POST /rides/{id}/transitions` |
| `#/my-rides` | 我的发布 | `GET /rides/mine`（RFC-008）、下架 transitions |
| `#/messages` | 会话列表 | `GET /chat-sessions` |
| `#/chat/:id` | 聊天 | WS `/ws/v1/chat` + `GET/DELETE …/messages`、`POST …/read` |
| `#/profile` | 我的 | `PUT /users/me`（头像 base64）、`GET /rides/mine` 入口 |
| `#/feedback` | 意见反馈 | `POST /feedback`（RFC-006） |

## 红线（与 AGENTS.md 一致）

- 动态插值进 `innerHTML` 必须过 `utils/format.js` 的 `escapeHTML`。
- 破坏性操作（下架/删消息）必须 `ui.js` 的 `confirmDialog` 二次确认，且失败重试复用同一幂等键。
- 每页一个 Primary Action；卡片视觉顺序：路线 > 联系 > 余位 > 车牌 > 距离。
- 域逻辑写成纯函数（JSDoc 标注输入输出），副作用只留在页面事件与 service 边界。
