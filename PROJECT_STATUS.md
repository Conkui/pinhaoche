# 项目状态

| 项目 | 当前值 |
| --- | --- |
| 当前版本 | V1.0.0-impl.3（CONTRACT_FREEZE_V1 + RFC-001~008；git 基线 ef2c207） |
| 当前阶段 | UI 美化（RFC-007 Ant Design 令牌）+ 发布页内联交互 + 我的发布/下架（RFC-008）已完成，独立 Review 通过、E2E 全绿；待微信开发者工具人工检查（C 清单） |
| 数据库版本 | V2__feedback.sql（Flyway，V1 冻结 + V2 反馈表） |
| API 版本 | /api/v1（RFC-001/002/004/006 端点已实现） |
| 已完成模块 | 契约全部文档；后端 auth/user/place/ride/chat/notification/importer/feedback/common/infrastructure；小程序 10 页面 + 服务层；预打车出发时间过期过滤 + EXPIRED 定时任务；分享文本解析预填；意见反馈；docker 一键环境 |
| 尚未完成模块（含阻塞原因） | ① 真实微信订阅消息网关——需微信 appid/secret + 订阅消息模板 id + 用户授权流程，缺外部凭证；② 真实 OCR Provider——需腾讯云/兼容 OCR API 密钥；③ 聊天 30 天清理定时任务——无外部依赖，待下个实现批次（纯代码任务）；④ 小程序订阅消息授权交互（wx.requestSubscribeMessage）——依赖①的模板 id；⑤ 压测（HTTP 300 QPS / WS 5000 连接）——需独立压测环境与工具（JMeter/k6），避免与验收环境混跑 |
| 已知问题 | 滴滴/花小猪无官方第三方 API（禁止抓包），导入走"分享文本预填→用户确认→手动填写"降级（规范 §17 允许）；WS 路由单实例实现 |
| 最近完整测试 | 2026-09-14（impl.3 批次）：三子 Agent（样式美化/交互重构/我的发布）+ 独立 Review Agent（APPROVE_WITH_FIXES，P1/P2 已修复）+ 单测 7/7 + tsc 0 错误 + E2E B1-B17 全 PASS（新镜像）。本轮修复：幂等键失败补偿删除（失败后同 key 重试不再 409）、POST /rides 与 /rides/import 返回 201 对齐 openapi、tabBar 选中色/成功色对齐 RFC-007、空预设时自定义地点可达。此前：2026-09-13 E2E 全 PASS；2026-09-14 冒烟+并发压测（50 并发抢 7 座 0 超卖，发现并修复 REPEATABLE READ 快照导致重试失败——join 事务改 READ_COMMITTED + 重试 8 次） |
| 下一阶段 | 微信开发者工具按 ACCEPTANCE C 清单人工验证 → Review/QA Agent 独立检查 → 接真实微信与 OCR（需外部资源） |

## 环境与端口

`docker compose up --build`（MySQL 宿主 23306 / Redis 16379 / RabbitMQ 25672+15672 / 后端 8080；宿主 3306/5672/6379 被占用或处于 Windows 排除段）。每小时自动续跑任务已建立（读 PROJECT_STATUS → 检查环境 → 补跑验收 → 更新状态）。

## 变更规则

冻结后，任何 Agent 不得直接修改 `openapi.yaml`、`db/migration/V1__init.sql`、Ride 状态或 WebSocket 协议。发现问题时在 `docs/rfc/` 新建 RFC（已接受：RFC-001~008），由集成负责人决定是否接受。

## 遗留 P2（Review 记录，下一批次处理）

- place-picker 页已无入口（孤儿页），决定去留并从 app.json 摘除；
- IdempotencyService 两个理论边界（Error 不补偿、记录落库阶段故障键卡住）可加固；
- 压测与真实微信/OCR 依赖外部资源（见上表阻塞原因）。
