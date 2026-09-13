# pinhaoche-miniprogram

拼好车 V1 微信小程序（原生 + TypeScript 严格模式 + WeUI 风格）。

## 结构

- `services/`：唯一副作用边界。`request.ts`（HTTP+幂等键）、`ws.ts`（WebSocket 三态/重连）、各领域 API。
- `utils/format.ts`：纯函数展示格式化（禁副作用）。
- `pages/`：9 页面，见根目录 `docs/UI_API_MAPPING.md` 的页面↔接口映射。
- `config/index.ts`：BASE_URL / WS_URL（本地联调 127.0.0.1，上线换 HTTPS/wss 域名）。

## 开发

1. 微信开发者工具导入本目录（需勾选"不校验合法域名"以连本地后端）。
2. `npm i -D miniprogram-api-typings` 获得完整类型（当前 `types/placeholder.d.ts` 仅为占位）。
3. 后端：根目录 `docker compose up --build`。

## UI 迭代规则（重要）

- 改样式/交互只动 wxml/wxss/页面 ts；接口不满足需求 → 走 `docs/rfc/` RFC，禁止改服务层契约类型。
- 页面↔接口对照与"新页面登记"统一维护在 `docs/UI_API_MAPPING.md`。
- 视觉红线（UI_SPEC）：白卡片、浅灰底、系统蓝、每页一个 Primary、禁渐变/霓虹/复杂阴影；所有列表必须有加载/空态/失败重试/已满四态。

## Not Completed / Known Issues

- 订阅消息授权交互（wx.requestSubscribeMessage）未接（后端通知网关为 DEV 实现）
- 头像默认图 `images/avatar-default.png` 未放真实资源；未做"我的行程"列表
- types/placeholder.d.ts 类型不全，正式开发需装 miniprogram-api-typings
