# 拼好车（Pin Hao Che）V1

面向偏僻学校学生的拼车微信小程序。**当前版本 `V1.0.0-impl.5`（CONTRACT_FREEZE_V1 + RFC-001~011），已可运行**：
后端 Spring Boot 3 模块化单体 + 微信原生 TypeScript 小程序 + docker compose 一键环境。

> **想直接跑起来？** 看 [`docs/RUNBOOK.md`](docs/RUNBOOK.md)（从零启动命令、后台管理页、mock→真实微信一键切换、FAQ）。
> **想知道做到哪了？** 看 [`PROJECT_STATUS.md`](PROJECT_STATUS.md)（已完成/未完成/已知问题）。
> **想验收？** 看 [`docs/ACCEPTANCE_V1.md`](docs/ACCEPTANCE_V1.md)（A/B/C/E 清单）。

## 两种运行模式

| 模式 | 一句话 | 适用 |
| --- | --- | --- |
| **mock**（默认） | 不需要微信 AppSecret，登录 code 直接当 openid，clone 下来就能跑 | 本地开发 / 自动化验收 |
| **real** | 真实 `wx.login` + 真实 openid + 真实头像昵称 | 体验"真实上线"、真机预览 |

切换只需改**一处 `.env`** + 小程序 `config.ts` 的 `MODE`（两边必须同时切，否则登录必然 401）。
逐步命令见 [`docs/RUNBOOK.md` 第 6 节](docs/RUNBOOK.md)。

## 目录结构

```
.
├── docker-compose.yml        # 一键环境：MySQL 8 / Redis 7 / RabbitMQ 3.13 / 后端（均从根目录 .env 读配置）
├── .env.example              # 环境变量模板：cp 成 .env 即可，含 mock/real 两段
├── openapi.yaml              # 【冻结契约】唯一接口事实源
├── db/migration/V1__init.sql # 【冻结契约】初始表结构（后端经 Flyway 引用）
├── docs/
│   ├── RUNBOOK.md            # 从零启动 / 模式切换 / 故障排查（运维入口）
│   ├── ACCEPTANCE_V1.md      # V1 验收清单 A/B/C/E
│   ├── ARCHITECTURE.md STATE_MACHINE.md WEBSOCKET_PROTOCOL.md PRODUCT_SPEC.md UI_SPEC.md
│   ├── CONTRACT_FREEZE_V1.md # 冻结范围与规则
│   └── rfc/                  # RFC-001~011（任何契约变更必须先提 RFC）
├── scripts/
│   ├── e2e.sh                # 验收 B 清单端到端脚本
│   └── smoke-profile.sh      # RFC-011 资料接口冒烟（头像/昵称/文件静态读取）
├── server/                   # 后端（Spring Boot 3.3.4 / Java 21）→ server/README.md
└── miniprogram/              # 小程序（原生 + TypeScript 严格模式）→ miniprogram/README.md
```

## 技术栈

- **后端**：Spring Boot 3.3.4 / Java 21 模块化单体（`auth` `user` `place` `ride` `chat` `notification`
  `importer` `admin` `common` `infrastructure`），MySQL 8 + Flyway，Redis 7（token/在线/未读/冷却），
  RabbitMQ + Outbox 事件。模块间只经 application service / DTO 交互，禁止跨模块访问 repository。
- **小程序**：微信原生 + TypeScript 严格模式 + Ant Design 5 风格视觉（主色 `#1677ff`）；
  领域逻辑为纯函数，副作用只存在于 `services/` 边界。
- **部署**：docker compose（本地）；生产 Nginx + HTTPS（硬约束见 RUNBOOK 第 10 节）。

## 快速开始（TL;DR）

```bash
cp .env.example .env                 # 填完所有 REPLACE_WITH_RANDOM_* 后再启动；mock 无需微信凭证
docker compose up -d --build         # 起 MySQL/Redis/RabbitMQ/后端
# 后端存活探针
curl -s -o /dev/null -w "%{http_code}\n" -H "X-Admin-Key: <.env 中的 ADMIN_KEY>" \
  http://127.0.0.1:8080/api/v1/admin/places     # 期望 200
# 后台管理页：浏览器打开 http://127.0.0.1:8080/admin/index.html （填 .env 中的 ADMIN_KEY）
# 小程序：微信开发者工具导入 E:\coTesx\miniprogram（不是仓库根目录），勾选"不校验合法域名"后编译
```

## V1 核心链路

登录 → 选地点 → 发布预打车 → 联系聊天 → 叫车 → 导入"已打上" → 微信提醒 → 加入 → 车主"不再等人"下架。
V1 **不使用地图/GPS/模糊搜索**；地点只有管理员预设与用户自定义两种；
"已打上"导入因滴滴/花小猪无官方第三方 API（禁止抓包），降级为"分享文本解析预填 → 用户确认 → 手动填写"。

## 契约与协作规则

- 冻结后**不得直接修改**：`openapi.yaml`、`db/migration/V1__init.sql`、Ride 状态机、WebSocket 协议、公共 DTO。
  发现问题在 `docs/rfc/` 新建 RFC，由集成负责人决定是否接受。
- 事实来源优先级：**源码 > openapi.yaml > db/migration > docs/ > PROJECT_STATUS.md > Agent 对话描述**。
- 产品红线（过期预打车单绝不展示、意见反馈入口、"已打上"合规降级、验收先行）见 [`AGENTS.md`](AGENTS.md)。
