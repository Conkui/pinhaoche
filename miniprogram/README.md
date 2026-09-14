# pinhaoche-miniprogram

拼好车 V1 微信小程序（原生 + TypeScript 严格模式 + Ant Design 5 风格视觉）。

## 结构

- `config.ts`：**单文件**全局配置（`BASE_URL` / `WS_URL` / `SERVER_ORIGIN` / `MODE` / `DEV_MOCK_LOGIN`）。
  **必须保持单文件**——微信开发者工具的 TS 模块加载不支持目录索引导入（`require('../config')`
  不会回退到 `config/index.ts`），历史上建成目录后启动即白屏。**不要再建 `config/` 目录。**
- `services/`：唯一副作用边界。`request.ts`（HTTP + 幂等键 + 401 自动重登）、`ws.ts`（WebSocket 三态/重连）、
  `auth.ts`（登录 code 获取）、`user.ts`（资料更新：头像压缩 + base64 + `PUT /users/me`）、
  以及 `place.ts` / `ride.ts` / `chat.ts` / `feedback.ts` 领域 API。
- `utils/format.ts`：纯函数展示格式化（禁副作用），含 `absoluteUrl(path, origin)` —— 把后端返回的
  头像相对路径拼成可加载的绝对 URL。
- `pages/`：11 个页面（`app.json` 登记），核心 4 个 Tab = 实时拼 / 找拼友 / 消息 / 我的。
  页面↔接口映射见根目录 `docs/UI_API_MAPPING.md`。
- `types/`：已不再使用 `types/placeholder.d.ts`（原占位声明已删除），类型统一切换到
  已安装的 `miniprogram-api-typings`。

## 模式开关（重要）

`config.ts` 里只有一处模式开关，其余常量由它派生：

```ts
export const MODE: 'mock' | 'real' = 'mock'
export const DEV_MOCK_LOGIN = MODE === 'mock'   // mock 时用固定 dev code 登录
```

| `MODE` | 后端要求 | 登录行为 | 适用 |
| --- | --- | --- | --- |
| `'mock'`（默认） | `WECHAT_MOCK=true` | 用 storage 里持久化的**固定 dev code**（身份稳定，避免"我的发布"看不到旧单） | 本地联调、自动化验收 |
| `'real'` | `WECHAT_MOCK=false` + 真实 `WECHAT_APPID`/`WECHAT_SECRET` | 真实 `wx.login` → 微信换真实 openid | 体验真实上线、真机预览 |

**两边必须同时切**：`MODE='real'` 而后端仍是 mock（或反过来）登录必然 401。
逐步命令见根目录 [`docs/RUNBOOK.md`](../docs/RUNBOOK.md) 第 6 节。

## 头像与昵称（RFC-011）

"我的"页支持真实头像昵称：

- 头像用 `<button open-type="chooseAvatar">`，昵称用 `<input type="nickname">`；
  **不用 `wx.getUserProfile`**——微信 2022-10-25 起对未在改版前用过 `wx.getUserInfo` 的小程序
  一律返回匿名数据（昵称"微信用户"+ 灰色默认头像），本项目基础库 3.4.10 属于该范围。
- 保存链路：`wx.compressImage` 压缩 → `readFile` 转 base64 → `PUT /api/v1/users/me {nickname, avatarBase64}`；
  成功后整体覆盖本地 `phc_user` 缓存并回写 storage，页面立即可见。
- 后端返回 `avatar` 为**相对路径** `/files/<uuid>.png`，前端用 `utils/format.ts` 的
  `absoluteUrl(avatar, SERVER_ORIGIN)` 拼成绝对 URL（不把 host 写进库，换域名只改 `config.ts`）。
- 真机 `input type="nickname"` 聚焦时可自动填充微信昵称；**开发者工具模拟器不保证自动填充，工具内需手填**（微信客户端行为）。
- 头像为空时用昵称首字符色块兜底（无任何本地图片依赖，不会 404）。

## 开发

1. 微信开发者工具导入**本目录**（不是仓库根目录），勾选
   "不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书"。
   `project.config.json` 已含 `urlCheck: false` + appid `wxe956226c7e7fa726` + 基础库 3.4.10。
2. 后端：仓库根目录 `cp .env.example .env && docker compose up -d --build`（详见 `docs/RUNBOOK.md`）。
3. 类型：`miniprogram-api-typings` 已安装在 `devDependencies`，`tsconfig.json` 以
   `"types": ["miniprogram-api-typings"]` 引用；检查命令 `npx tsc --noEmit`（期望 0 error）。

## UI 迭代规则（重要）

- 改样式/交互只动 wxml/wxss/页面 ts；接口不满足需求 → 走 `docs/rfc/` RFC，禁止改服务层契约类型。
- 页面↔接口对照与"新页面登记"统一维护在 `docs/UI_API_MAPPING.md`。
- 视觉令牌（RFC-007，参考 Ant Design 5）：主色 `#1677ff`、渐变主按钮、卡片浅投影、功能色体系；
  所有列表必须有加载/空态/失败重试/已满四态；每页一个 Primary Action。

## Not Completed / Known Issues

- 订阅消息授权交互（`wx.requestSubscribeMessage`）未接——依赖后端真实订阅消息网关与模板 id（见 PROJECT_STATUS 阻塞项）。
- `pages/place-picker` 已无入口（发布/导入页改为本页内联下拉，RFC-007），属孤儿页，待下批次从 `app.json` 摘除。
- 未做"我的行程"独立列表页（当前用"我的发布"覆盖发布者视角）。
- `Promise.finally` 在低版本基础库的兼容性尚未在真机验证（Review P2-5）。
- 头像存后端本地盘（`data/uploads`），多实例/无卷重建会丢，V2 接对象存储。
