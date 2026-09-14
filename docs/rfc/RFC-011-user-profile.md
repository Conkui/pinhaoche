# RFC-011：真实微信用户信息（头像昵称填写能力 + 资料更新接口）

## 问题
V1 登录只做静默 `wx.login`（RFC-002），`AppUser` 落库时的昵称是占位值 `同学XXXX`、`avatar` 恒为 null
（见 `AppUser` 构造函数注释"V1 不做资料编辑页"）。用户要求"在微信开发者工具体验真实上线的情况，
包括登录授权真实微信用户信息"，当前实现无法满足：小程序内不存在任何获取真实昵称/头像的交互，
即使拿到了也没有任何接口能把它们存下来。

`wx.getUserProfile` 不能作为方案：微信自 2022-10-25 起，对未在改版前使用过 `wx.getUserInfo` 的小程序，
该接口一律返回匿名数据（昵称"微信用户" + 灰色默认头像），本项目基础库 3.4.10，属于该范围。
唯一合规路径是**头像昵称填写能力**：`<button open-type="chooseAvatar">` 取用户选择的头像、
`<input type="nickname">` 取用户填写的昵称（真机上聚焦时可自动填充微信昵称）。

该能力产出的两个值都不能直接用：
- 头像是本机临时文件路径（`wxfile://tmp_xxx`），仅当前设备当前会话可读，换设备/清缓存即失效；
- 昵称是客户端传入的任意字符串，不满足"身份只来自 token"的安全约定，必须服务端校验后落库。

## 建议

### 1. 新增 `PUT /api/v1/users/me`（资料更新，幂等替换）
- 鉴权：走既有 `AuthInterceptor`，**userId 只从 token 解析**，请求体不接受任何用户 id 字段。
- 请求体：`{ "nickname"?: string, "avatarBase64"?: string }`，两者至少一个非空，空对象 → 400 `VALIDATION_ERROR`。
- 响应 200：`{ id, nickname, avatar }`（复用 `LoginResponse.user` 结构，小程序端可整体覆盖本地缓存）。
- 语义：字段存在即替换（昵称走校验+净化，头像走解码+落盘），字段缺省即保持原值；同内容重复提交结果一致（幂等）。
- 为什么用 `PUT` 而非 `PATCH`：`services/request.ts` 的 method 联合类型只含 GET/POST/PUT/DELETE，
  且"设置资料"本身是幂等替换语义，用 PUT 无需改动小程序请求封装（少改一处公共代码）。

### 2. 头像存储与访问
- 存储：本地磁盘 `app.upload-dir`（新增配置项，默认 `./data/uploads`，容器内挂载卷持久化），
  落盘文件名由服务端生成 `<randomUUID>.<ext>`，**绝不复用客户端文件名**（杜绝路径穿越）。
- 访问：`GET /files/**` 只读静态资源映射（不在 `/api/v1/**` 下，因此不经过用户 token 拦截）。
  为什么必须免鉴权：小程序 `<image src>` 无法携带 `Authorization` 头，若要求 token 则头像永远 404。
  代价是"知道文件名即可读"，用不可枚举的 UUID 文件名缓解，且头像本身是用户主动公开的信息。
- 校验（安全边界，必须在服务端做）：
  - 解码后原始字节 ≤ 2 MiB，超过 → 400；
  - 按**魔数**判定真实类型，仅允许 JPEG/PNG/GIF/WEBP；**显式拒绝 SVG**（SVG 可内嵌脚本，
    经静态资源直接返回即为存储型 XSS 面）；
  - 扩展名由魔数推导，与客户端声明无关（不做 Content-Type 信任）。
- 落库值：`app_user.avatar`（VARCHAR(512)，已存在，**无需迁移**）存**相对路径** `/files/<name>`，
  由小程序拼接服务端 origin 得到可加载 URL（便于换域名，不把 host 写进库）。

### 3. 昵称校验
- 长度 1–20（按 `String.length()` 计，与 `VARCHAR(100)` 有充足余量）；
  先 `TextSanitizer.clean`（复用 RFC-010 的公共净化入口）再判长度——净化后为空 → 400。
- 不做昵称唯一性（校园场景同名正常），不做内容审核（V1 无内容审核能力，记入 Known Issues）。

### 4. 不新增错误码
全部复用既有 `VALIDATION_ERROR`(400) / `UNAUTHORIZED`(401) / `NOT_FOUND`(404)，不改 `ErrorCode` 枚举。

## 影响模块
| 模块 | 改动 |
| --- | --- |
| 小程序 `config.ts` | 单点模式开关 `MODE: 'mock' \| 'real'`；派生 `BASE_URL`/`WS_URL`/`DEV_MOCK_LOGIN`；新增 `SERVER_ORIGIN`（拼头像绝对 URL） |
| 小程序 `services/user.ts`（新增） | 本地临时文件 → `wx.compressImage` 压缩 → `readFile` base64 → `PUT /users/me`；成功后回写 `phc_user` |
| 小程序 `pages/profile` | 头像 `<button open-type="chooseAvatar">` + 昵称 `<input type="nickname">` + 保存 |
| 后端 `user` 模块 | 新增 `UserProfileController` / `UserProfileService` / `AvatarStorage` / 静态资源配置 |
| 后端 `application.yml` | 新增 `app.upload-dir`（`${UPLOAD_DIR:./data/uploads}`） |
| 部署 | `docker-compose.yml` 增加上传目录卷；`.env` 支持 mock/real 切换（`WECHAT_MOCK`/`WECHAT_APPID`/`WECHAT_SECRET`/`ADMIN_KEY`） |

`openapi.yaml` 属冻结契约：本 RFC 被接受后由 Integration 一次性追加 `/users/me` 路径定义，
**不修改任何既有字段语义**——`LoginResponse.user.avatar` 早已存在（nullable），本次只是让它开始有真实值。

## 已知限制（记入 PROJECT_STATUS/README，不隐瞒）
1. 头像存本地盘：多实例部署或容器无卷重建会丢；接对象存储属 V2（需新增 `FileStorage` 接口 + 适配器）。
2. 头像静态资源无鉴权（原因见上），泄露 UUID 文件名即可读。
3. 真机 `input type="nickname"` 自动填充微信昵称由微信客户端决定，开发者工具模拟器不保证自动填充；
   工具内需手动输入昵称——这是平台行为，非实现缺陷。
4. 已有 mock 用户的历史占位昵称不会被自动改写，需用户在"我的"页主动保存一次。

状态：已由 Integration 评审接受（用户 2026-09-14 指令"体验真实上线 + 登录授权真实微信用户信息"）。
