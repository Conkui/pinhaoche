# UI ↔ 接口映射（UI_API_MAPPING）

> 本文档是前端页面与后端接口的对照台账，专供 UI 迭代使用：**UI 可反复改版，但每处改动必须回写此表**，
> 保证"改 UI 不改契约"可审计；若某个 UI 需求发现接口不满足，走 `docs/rfc/` RFC 流程，不得私自改 openapi。
>
> 交互约定：所有写操作带 `Idempotency-Key` 请求头（失败重试必须复用同一个 key）；
> 消息实时收发走 WebSocket，REST 只承担建会话/补偿拉取/已读。

## 页面 → 接口总表

| 页面（路径） | Primary Action（UI_SPEC） | 接口 / 协议 | 请求 | 响应/结果 | 备注 |
| --- | --- | --- | --- | --- | --- |
| 已打上 `pages/booked` | 立即联系车主 | ① `GET /api/v1/rides?view=BOOKED`<br>② `POST /api/v1/chat-sessions`<br>③ WebSocket `/ws/v1/chat` | ② `{rideId, peerUserId=ownerId}` | ② `ChatSession` → 跳聊天页 | 卡片视觉顺序：路线/联系/余位/车牌/距离；满员按钮置灰 |
| 预打车 `pages/prebook` | 联系发布者 | ① `GET /api/v1/rides?view=PREBOOK`<br>② `POST /api/v1/chat-sessions` | 同上 | 同上 | 整卡点击进详情；卡片顺序：路线>时间>人数>备注 |
| 消息 `pages/messages` | 打开会话 | `GET /api/v1/chat-sessions`（RFC-001） | - | `ChatSessionSummary[]`（含 unreadCount） | 未读角标取 unreadCount |
| 我的 `pages/profile` | 重新登录 | `wx.login` + `POST /api/v1/auth/login`（RFC-002） | `{code}` | `LoginResponse {token, user}` | token 存 storage，全局 Bearer |
| 发布预打车 `pages/publish` | 发布 | `POST /api/v1/rides` | `CreateRideRequest` + `Idempotency-Key` | 201 `Ride` | 提交按钮持有幂等键，失败重试复用 |
| 发布已打上 `pages/import` | 确认并发布 | `POST /api/v1/rides/import`（RFC-004） | `ImportRideRequest` + `Idempotency-Key` | 201 `Ride`（status=BOOKED） | 车牌/地点/距离均用户确认；距离标注"截图参考值"（规范 §19） |
| 地点选择 `pages/place-picker` | 选用地点 | `GET /api/v1/places` | - | `Place[]` | 预设列表 + 底部自定义填写（CUSTOM 不入公共库） |
| 行程详情 `pages/ride-detail` | 加入 / 不再等人 / 已到达 | ① `GET /api/v1/rides/{id}`<br>② `POST /api/v1/rides/{id}/join`<br>③ `POST /api/v1/rides/{id}/transitions` | ② `Idempotency-Key`<br>③ `{targetStatus, version}` | ②③ 返回最新 `Ride` | 迁移动作：BOOKED→IN_PROGRESS（不再等人）、IN_PROGRESS→ARRIVED（到达）；危险操作二次确认 |
| 聊天 `pages/chat` | 发送 | ① `GET /chat-sessions/{id}/messages?afterId=`<br>② WS `SEND_MESSAGE`<br>③ WS `ENTER_SESSION/EXIT_SESSION`<br>④ `POST /chat-sessions/{id}/read` | ② `{clientMsgId, sessionId, content}` | ② `ACK`（含 messageId）/`ERROR` | 三态气泡 sending/acked/failed；失败重试复用 clientMsgId；断线重连后 afterId 补偿 |
| 我的·反馈入口 `pages/feedback` | 提交反馈 | `POST /api/v1/feedback`（RFC-006） | `{content(1..500)}` | 204 | 入口放"我的"页，不与主功能抢位置 |
| 导入·分享文本 | 解析预填 | 本地纯函数 `utils/shareText.ts`（无网络请求） | 分享文本 | provider/车牌/剩余距离候选 | 结果仅预填，提交仍走 `POST /rides/import` 用户确认（规范 §17/§18） |

## 列表时间语义（用户红线：时间效益）

- `view=PREBOOK`：只返回 `depart_time` 尚未到达的 PUBLISHED/MATCHING 单；已过出发时间的单对所有人不可见，且服务端每 60s 将其状态落为 `EXPIRED`（`RideExpireJob`）。
- `view=BOOKED`：BOOKED 状态单按更新时间倒序；车主"不再等人"（→IN_PROGRESS）后自动消失。

## 管理端（非小程序契约，RFC-005）

| 功能 | 接口 | 鉴权 |
| --- | --- | --- |
| 地点列表（含停用） | `GET /api/v1/admin/places` | `X-Admin-Key` |
| 新增地点 | `POST /api/v1/admin/places` | 同上 |
| 重命名 | `PUT /api/v1/admin/places/{id}` | 同上 |
| 排序 | `PUT /api/v1/admin/places/reorder` | 同上 |
| 启用 | `POST /api/v1/admin/places/{id}/enable` | 同上 |
| 停用（软删） | `DELETE /api/v1/admin/places/{id}` | 同上 |

## UI 迭代时的检查清单

1. 改动只影响展示/交互 → 只改 wxml/wxss/页面 ts，禁止改 `openapi.yaml` 与服务层契约类型。
2. 新 UI 需要新数据字段 → 先确认 `Ride`/`Message` 等 schema 是否已有；没有 → 新建 RFC，接受后再改。
3. 新增页面 → `app.json` 注册 + 在本表追加一行。
4. 视觉规范红线（UI_SPEC）：白卡片、浅灰底、系统蓝主色、每页一个 Primary Action、禁渐变/霓虹/复杂阴影。
5. 所有列表页必须实现：加载中 / 空态 / 失败重试 / 已满·过期状态（模板见各页面 wxml 顶部三态块）。

## 环境地址（miniprogram/config/index.ts）

- 本地联调：`http://127.0.0.1:8080/api/v1`（docker-compose 起的后端，微信开发者工具需勾选"不校验合法域名"）
- WebSocket：`ws://127.0.0.1:8080/ws/v1/chat?token=`
- 上线：替换为 HTTPS/wss 域名并在小程序后台配置 request/socket 合法域名。
