# 拼好车 RUNBOOK（从零启动 / 一键切真实模式 / 故障排查）

> 本文面向"拿到仓库的任何人"，命令可直接复制执行。Windows + Git Bash 环境已实测。
> 契约与验收清单见 [`ACCEPTANCE_V1.md`](ACCEPTANCE_V1.md)；当前版本状态见 [`../PROJECT_STATUS.md`](../PROJECT_STATUS.md)。

---

## 1. 前置要求

| 需要 | 说明 |
| --- | --- |
| [Docker Desktop](https://www.docker.com/products/docker-desktop/)（含 compose v2） | 唯一硬依赖。后端 Java 21、MySQL、Redis、RabbitMQ 全部跑在容器里 |
| [微信开发者工具](https://developers.weixin.qq.com/miniprogram/dev/devtools/download.html) 稳定版 | 小程序预览与调试 |
| Git | 拉代码 |
| 浏览器（Chrome/Edge） | 打开管理后台 |

**本机不需要**安装 JDK、Maven、MySQL、Redis。本机也不需要 Node —— 仅当你要跑可选的
`scripts/*.sh`（内部用 node 解析 JSON）或 `npx tsc --noEmit` 类型检查时才需要 Node 18+。

> 为什么强调这点：本仓库宿主机历史上只装了 JDK 8 + Maven 3.6.3，**无法编译 Java 21 源码**。
> 所有 Java 构建/单测一律走容器（见第 7 节），不要在宿主机直接 `mvn`。

验证 Docker 可用：

```bash
docker --version          # 期望 Docker version 24+ / 27+
docker compose version    # 期望 Docker Compose version v2.x
```

---

## 2. 从零启动（全新机器，完整命令序列）

```bash
# 1) 拉代码
git clone <仓库地址> coTesx
cd coTesx

# 2) 生成环境变量文件（默认即【A 本地联调】，不需要微信 AppSecret）
cp .env.example .env

# 3) 一键构建并启动全部 4 个容器（首次构建需下载镜像 + Maven 依赖，约 3~8 分钟）
docker compose up -d --build

# 4) 等待健康检查（MySQL 首次初始化约 20~40 秒）
docker compose ps
```

`docker compose ps` 应看到 **4 个容器**处于运行状态（server 为 `Up`，mysql 为 `Up (healthy)`）：

| 容器 | 宿主机端口 | 容器内端口 | 说明 |
| --- | --- | --- | --- |
| `mysql` | 23306 | 3306 | 业务事实源，`pinhaoche` 库 |
| `redis` | 16379 | 6379 | token / 在线状态 / 未读数 / 通知冷却 |
| `rabbitmq` | 25672 / 15672 | 5672 / 15672 | Outbox 事件消费；15672 是管理控制台（guest/guest） |
| `server` | 8080 | 8080 | Spring Boot 后端 + 静态管理后台 |

> 为什么端口不是默认值：宿主机 3306/5672/6379 常被本地已装的服务占用，或落在 Windows 的
> TCP 排除端口段内。用 23306/25672/16379 可绕开，容器内部仍是标准端口（不影响代码）。

查看后端启动日志，确认 Flyway 迁移与应用启动都成功：

```bash
docker compose logs -f server
# 期望看到（Ctrl+C 退出日志跟随，不会停容器）：
#   Flyway ... Successfully applied 2 migrations to schema `pinhaoche`
#   Started PinHaoCheApplication in x.xxx seconds
```

---

## 3. 验证后端活着

用**管理端**接口做存活探针（它只需 `X-Admin-Key`，不依赖登录）：

```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  -H "X-Admin-Key: dev-admin-key" \
  http://127.0.0.1:8080/api/v1/admin/places
# 期望输出：200
```

返回 `403` → 密钥不对（`.env` 里的 `ADMIN_KEY` 与命令不一致）。
返回 `000` / 连接失败 → 容器没起来或端口没映射，回到第 2 节看 `docker compose ps`。

> 为什么不用 `/places` 做探针：`GET /api/v1/places` 需要用户 token（`Authorization: Bearer ...`），
> 未登录恒为 401，无法区分"服务活着但没登录"和"服务挂了"。

---

## 4. 后台管理页面

浏览器打开：

```
http://127.0.0.1:8080/admin/index.html
```

1. 页面顶部输入框填 `dev-admin-key`（即 `.env` 的 `ADMIN_KEY`），点「保存」；
2. 四个 Tab 应正常出数据：**总览**（12 项标量指标 + 近 7 天趋势）、**反馈**（分页列表）、
   **拼车**（可按 status 过滤）、**地点**（预设地点增删改）。

**必须经后端访问，不能双击 HTML 文件用 `file://` 打开**：页面用 `fetch` 调同源 `/api/v1/admin/**`，
`file://` 协议下没有同源后端、且请求头 `X-Admin-Key` 会触发跨域预检，必然失败。该页是后端静态资源
（`server/src/main/resources/static/admin/index.html`），只能由 8080 端口提供。

生产环境三条硬要求：**换掉 `ADMIN_KEY`** + **只走 HTTPS** + **Nginx 层加 IP 白名单**（管理端无账号体系，
密钥就是唯一凭据）。

---

## 5. 小程序导入与运行

1. 打开微信开发者工具 → 「导入项目」；
2. 目录选择 **`<仓库根>/miniprogram`**（**不是仓库根目录**，根目录没有 `project.config.json`）；
3. AppID 已内置为 `wxe956226c7e7fa726`（真实注册的小程序），工具会自动读 `project.config.json`；
4. 进入后点右上角「详情」→「本地设置」，勾选：

   > ☑ 不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书

   （`project.config.json` 里已预置 `urlCheck: false`，但个人本地设置可能覆盖它，**手动确认一次最稳**）

5. 点「编译」，底部四个 Tab 可切换：**实时拼 / 找拼友 / 消息 / 我的**。

连接的是本机后端 `http://127.0.0.1:8080`，所以第 2 节的 compose 必须处于运行状态。

---

## 6. 两种模式对照表

| 维度 | 【A】mock（本地联调，默认） | 【B】real（真实微信） |
| --- | --- | --- |
| 后端 `.env` | `WECHAT_MOCK=true` | `WECHAT_MOCK=false` + `WECHAT_APPID` + `WECHAT_SECRET` |
| 小程序 `config.ts` | `MODE = 'mock'`（→ `DEV_MOCK_LOGIN = true`） | `MODE = 'real'`（→ 走真实 `wx.login`） |
| 登录 code 来源 | 固定 dev code（存 storage，身份稳定） | `wx.login` 返回的真实 code |
| 是否真的请求微信服务器 | 否（mock 网关把 code 直接当 openid） | 是（`jscode2session` 换真实 openid） |
| openid 形态 | 形如 `mock_<dev code>`（mock 网关在前缀 `mock_` 后直接拼 code） | 真实 openid（`o` 开头的 28 位串） |
| 需要 AppSecret | 不需要 | **需要**（唯一前置条件） |
| 身份是否跨设备稳定 | 同一份 storage 才稳定 | 天然稳定（微信 openid 恒定） |
| 改完要重启什么 | 改 `.env` 后重建 `server` 容器；改 `config.ts` 后工具重新编译 | 同左 |
| 怎么验证成功 | 进"我的"页即显示昵称 `同学XXXX`，无需任何授权 | "我的"页点「重新登录」后显示真实 openid 对应的账号；`app_user.openid` 不含 `mock_` 前缀 |

### 6.1 mock → real 逐步操作（照抄即可）

```bash
# ① 改 .env：把 WECHAT_MOCK 改成 false，填上 AppID 与 AppSecret（见 .env.example 的【B】段）
#    编辑 E:\coTesx\.env
#    WECHAT_MOCK=false
#    WECHAT_APPID=wxe956226c7e7fa726
#    WECHAT_SECRET=<你的 AppSecret>
#    ADMIN_KEY=<强随机密钥>          # 顺手换掉，默认值公开可见

# ② 只重建后端容器（MySQL/Redis/RabbitMQ 不动，业务数据保留）
docker compose up -d --build server

# ③ 确认后端真的读到新配置（应输出 false，若仍是 true 说明 .env 没生效）
docker compose exec server printenv WECHAT_MOCK

# ④ 改小程序模式：E:\coTesx\miniprogram\config.ts → MODE = 'real'

# ⑤ 微信开发者工具点「编译」，进"我的"页点「重新登录」
#    成功：登录返回的是真实 openid 账号；失败：见第 9 节 FAQ 的 401 排查
```

反向切回 mock：把 ①（`.env`）与 ④（`config.ts`）改回去，再执行 ②。

核对数据库里落的是真实 openid：

```bash
# --default-character-set=utf8mb4 不能省：不加时中文昵称会显示成 ??（客户端字符集问题，不是入库乱码）
docker compose exec mysql mysql -uroot -proot --default-character-set=utf8mb4 pinhaoche \
  -e "select id,openid,nickname from app_user order by id desc limit 5"
# real 模式：openid 不是 mock_ 开头；mock 模式：openid 形如 mock_<dev code>
```

---

## 7. 只跑后端构建 / 单测（本机无 JDK 21）

```bash
cd server        # 在仓库根目录下执行（下同）
MSYS_NO_PATHCONV=1 docker run --rm \
  -v "$(pwd -W):/build" \
  -v phc-maven-repo:/root/.m2 \
  -w /build \
  maven:3.9-eclipse-temurin-21 mvn -B test
```

两个参数为什么必需（Git Bash 特有的坑，漏掉会报路径错误或空目录）：

- **`MSYS_NO_PATHCONV=1`**：Git Bash 会把命令行里以 `/` 开头的参数当路径转换成 `C:\...`，
  于是 `-w /build` 被改写成 Windows 路径传给 Linux 容器，容器里找不到目录直接失败。该变量关闭转换。
- **`$(pwd -W)`**：`pwd` 在 Git Bash 里输出 `/e/<仓库目录>/server` 这种 MSYS 路径，宿主机 Docker
  无法挂载；`-W` 让它输出 Windows 原生路径 `E:/<仓库目录>/server`，才能正确挂进容器。

`-v phc-maven-repo:/root/.m2` 是具名卷缓存 Maven 依赖，第一次跑会下载依赖（几分钟），之后明显变快。
期望结果：`BUILD SUCCESS`，`Tests run: N, Failures: 0, Errors: 0`（当前基线 39/39）。

小程序侧类型检查（需要 Node）：

```bash
cd miniprogram && npx tsc --noEmit    # 期望 0 error
```

---

## 8. 真实微信用户信息（头像 + 昵称）

微信自 2022-10-25 起，`wx.getUserProfile` 对未在改版前使用过 `wx.getUserInfo` 的小程序
**一律返回匿名数据**（昵称"微信用户" + 灰色默认头像），本项目基础库 3.4.10 属于该范围，
所以**不能用它**拿真实头像昵称。合规路径是微信的「**头像昵称填写能力**」：

1. 进"我的"页 → 点头像区域 → 弹出微信的图片选择/拍照界面（组件是 `<button open-type="chooseAvatar">`）；
2. 点昵称输入框 → 输入昵称（组件是 `<input type="nickname">`）。
   **真机上聚焦时可自动填充微信昵称**；开发者工具模拟器不保证自动填充，**工具内需手动输入**——
   这是微信客户端行为，不是实现缺陷（见 RFC-011 已知限制 3）；
3. 点「保存资料」→ 小程序侧 `services/user.ts`（唯一的副作用边界）用 `wx.compressImage` 压缩 →
   `readFile` 转 base64 → 发送前做体积拦截 → `PUT /api/v1/users/me {nickname, avatarBase64}`；
4. 后端校验后落盘到容器内 `/app/data/uploads`（宿主机 `<仓库根>/data/uploads`），
   库里 `app_user.avatar` 存**相对路径** `/files/<uuid>.png`，小程序用 `utils/format.ts` 的
   `absoluteUrl(avatar, SERVER_ORIGIN)` 拼成绝对 URL 加载（`SERVER_ORIGIN` 定义在 `config.ts`）；
5. 保存成功后"我的"页头像与昵称立即更新（前端整体覆盖本地 `phc_user` 缓存）。

服务端校验（安全边界，客户端无法绕过）：解码后 ≤ 2 MiB；按**魔数**判定类型，仅允许
JPEG/PNG/GIF/WEBP，**显式拒绝 SVG**（SVG 可内嵌脚本，静态资源直出等于存储型 XSS）；
文件名由服务端生成 UUID，绝不复用客户端文件名（防路径穿越）。

> 历史用户注意：mock 时期产生的占位昵称（`同学XXXX`）不会自动改写，需在"我的"页保存一次。

---

## 9. FAQ / 常见故障

| 现象 | 原因 | 解法 |
| --- | --- | --- |
| `docker compose up` 报端口被占用 / `bind: address already in use` | 宿主机 3306/5672/6379 被本地已装的 MySQL/RabbitMQ/Redis 占用，或端口落在 Windows 排除段（如 6336-6435、5636-6635） | 本项目已改用 23306/25672/16379 绕开；若仍冲突，改 `docker-compose.yml` 左侧宿主端口（冒号左边），容器内不要动 |
| 开发者工具打开就白屏 | 曾经的 `config/index.ts` 目录形式：工具 TS 模块加载不回退目录索引，`require('../config')` 解析失败 | 已拍平为单文件 `miniprogram/config.ts`；**不要再建 `config/` 目录**，`config` 必须保持单文件 |
| 请求 `http://127.0.0.1:8080` 全部失败（`request:fail`） | 工具未勾选"不校验合法域名"（个人本地设置会覆盖 `project.config.json`） | 详情 → 本地设置 → 勾选"不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书" |
| 登录 401 / 提示「微信登录失败」 | ① AppSecret 填错或被重置；② AppID 与 AppSecret 不属于同一个小程序；③ 两边模式不匹配：`WECHAT_MOCK=true` 却 `MODE='real'`（后端拿真实 code 当 openid 但没配 secret），或 `WECHAT_MOCK=false` 却 `MODE='mock'`（后端拿 dev code 去微信换 openid，微信报 `invalid code`） | 依次核对：`docker compose exec server printenv WECHAT_MOCK`、`.env` 的 appid/secret、`config.ts` 的 `MODE`；**两边必须同时切**。改完记得 `docker compose up -d --build server` 并重新编译小程序 |
| 登录 429 `RATE_LIMITED` | 登录接口按 IP 限流 20 次/分钟（RFC-010），写脚本连发时很容易触发 | 等 60 秒再试；批量脚本里加 `sleep`。生产环境限流依赖 Nginx 覆盖式 XFF 配置（见 RFC-010 部署硬约束） |
| `docker compose down` 之后所有请求 401，要重新登录 | token 存在 Redis 里，`down` 会连容器数据一起清（Redis 未挂持久卷） | 属预期行为（缓存类数据本就该可从 MySQL 重建）：重进"我的"页点「重新登录」即可。想保留登录态用 `docker compose stop/start` 而不是 `down` |
| mock 模式下"我的发布"看不到之前发的单 | 身份漂移：早期版本 dev 下每次 `wx.login` 都生成随机 code → 每次重登都是新用户 | 已修复：`MODE='mock'` 时固定 dev code 存 storage，身份恒定。若你是漂移期间产生的旧数据，清掉工具缓存重新登录一次即归位 |
| 容器重建（`up --build`）后旧头像还在吗 | `./data/uploads:/app/data/uploads` 是宿主机绑定挂载，**文件仍在** | 无需处理；验证：在仓库根 `ls data/uploads`。反例提醒：若删掉该卷或换机器，本地盘上的头像会丢（V2 接对象存储解决） |
| 上传头像返回 400 | 图片超 2 MiB、或格式是 SVG、或昵称净化后为空 | 换普通照片（JPEG/PNG）、压缩后重试；昵称不要只填空白字符 |
| 上传头像返回 413 Request Entity Too Large | 生产 Nginx 默认 `client_max_body_size 1m`，而头像 base64 上限约 2.8MB | 在 Nginx 的 `server`/`location` 加 `client_max_body_size 8m;` 后重载（见第 10 节第 5 条） |
| 保存资料后旧昵称/旧头像又回来了 | 只改了 `.env` 没改 `config.ts` 的 `MODE`（或反之），实际生效的是另一套模式 | 两边必须同时切；见第 6 节对照表 |
| 头像 URL 404 | `UPLOAD_DIR` 与卷挂载点不一致，或文件所属容器已换 | 确认 `docker-compose.yml` 里 `UPLOAD_DIR` 与 `./data/uploads:/app/data/uploads` 目标路径一致，然后重建 server |
| Git Bash 里 curl 传中文 JSON 变乱码 | Git Bash 对 argv 做编码转换 | 脚本统一写临时文件后用 `--data-binary @file`（见 `scripts/e2e.sh`、`scripts/smoke-profile.sh`），不要用 `-d '{"nickname":"中文"}'` |

---

## 10. 真机预览 / 上线的硬约束（不要自欺欺人）

**本机的 `http://127.0.0.1:8080` 只在「微信开发者工具 + 勾选不校验合法域名」下可用。**
它不是"上线可用"的形态，理由如下：

- 真机预览、体验版、正式版**不读取**"不校验合法域名"这个开发者工具开关，微信会强制校验域名合法性；
- 微信后台**只接受已备案的 HTTPS 域名**，不接受 IP、不接受 `http://`、不接受自签名证书。

因此上线前必须完成：

1. 准备一个**公网可访问、已 ICP 备案的域名**，配置 TLS 证书（Nginx 反代到 8080）；
2. 微信公众平台 →「开发管理」→「开发设置」→「服务器域名」配置：
   - **request 合法域名**：`https://<你的域名>`（登录、业务接口、`/files/**` 头像一律走它）
   - **socket 合法域名**：`wss://<你的域名>`（聊天 WebSocket `/ws/v1/chat`，必须是 `wss://` 而不是 `ws://`）
3. 小程序 `config.ts` 只需把 **`SERVER_ORIGIN`** 换成该 HTTPS 域名（`BASE_URL`/`WS_URL` 由它派生，
   `ws://` 自动变 `wss://`）并重新上传代码；
4. Nginx 必须落实 **RFC-010 的覆盖式 XFF 约束**（否则登录限流可被伪造 XFF 绕过）：
   由 Nginx 覆盖写入 `X-Forwarded-For`，不信任客户端传入的同名头；
5. Nginx 还要加 **`client_max_body_size 8m;`**（默认仅 1m）：头像走 base64 JSON，客户端上限约 2.8MB，
   不加会直接 413；
6. `/files/**` 是**免鉴权**静态资源（小程序 `<image src>` 无法携带 `Authorization`，要求 token 则头像永远 404），
   且不受 RFC-010 限流覆盖——这是已知权衡（文件名是 UUID、不可枚举，头像是用户主动公开的信息）。
   不要为了"看起来更安全"给它加 token 校验，那会直接打死头像显示；如需更严再叠加 Referer/域名校验；
7. 更换 `ADMIN_KEY`（默认 `dev-admin-key` 等于公开密码），管理后台加 IP 白名单或只在内网暴露；
8. `MODE` 必须是 `'real'`（`DEV_MOCK_LOGIN` 由它派生，无需单独改；保持 mock 等于线上所有人都登录成同一个 dev 账号）；
9. 上传目录改为持久化对象存储或挂载网络盘（当前为本地盘，多实例部署或容器无卷重建会丢头像）。

未完成上述 1~4 时，请不要把小程序提交审核或发给别人"试一下"——只会得到一串域名不合法的报错。

---

## 11. 验收清单

交付前按 [`ACCEPTANCE_V1.md`](ACCEPTANCE_V1.md) 执行：

- **A 清单**：自动化门槛（后端容器 `mvn -B test`、`tsc --noEmit`、Flyway 迁移）
- **B 清单**：端到端模拟调用（`bash scripts/e2e.sh`，覆盖登录→发布→加入→聊天→叫车→下架）
- **C 清单**：微信开发者工具人工检查（导入即运行、四 Tab、发布全流程、WS 送达）
- **E 清单**：真实微信模式上线前人工确认（`.env` 切 real、真实 openid、头像昵称落库与加载）

另：`bash scripts/smoke-profile.sh` 是资料接口（`PUT /users/me` + `/files/**`）的独立冒烟脚本，
覆盖 E2/E3/E4/E5/E6 的接口侧断言，可在 compose 起来后直接跑。
