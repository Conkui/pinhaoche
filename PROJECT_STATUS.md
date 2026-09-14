# 项目状态

| 项目 | 当前值 |
| --- | --- |
| 当前版本 | V1.0.0-impl.4（CONTRACT_FREEZE_V1 + RFC-001~010；git 基线 f81ee8d） |
| 当前阶段 | 管理员 Web 后台（RFC-009：统计/反馈/拼车/地点，http://localhost:8080/admin/index.html）+ 接口防刷限流与输入净化（RFC-010）已交付；独立 Review 通过（P2 已处理）、E2E B1-B21 全绿 |
| 数据库版本 | V2__feedback.sql（Flyway，V1 冻结 + V2 反馈表） |
| API 版本 | /api/v1（RFC-001/002/004/006 端点已实现） |
| 已完成模块 | 契约全部文档；后端 auth/user/place/ride/chat/notification/importer/feedback/common/infrastructure；小程序 10 页面 + 服务层；预打车出发时间过期过滤 + EXPIRED 定时任务；分享文本解析预填；意见反馈；docker 一键环境 |
| 尚未完成模块（含阻塞原因） | ① 真实微信订阅消息网关——需微信 appid/secret + 订阅消息模板 id + 用户授权流程，缺外部凭证；② 真实 OCR Provider——需腾讯云/兼容 OCR API 密钥；③ 聊天 30 天清理定时任务——无外部依赖，待下个实现批次（纯代码任务）；④ 小程序订阅消息授权交互（wx.requestSubscribeMessage）——依赖①的模板 id；⑤ 压测（HTTP 300 QPS / WS 5000 连接）——需独立压测环境与工具（JMeter/k6），避免与验收环境混跑 |
| 已知问题 | 滴滴/花小猪无官方第三方 API（禁止抓包），导入走"分享文本预填→用户确认→手动填写"降级（规范 §17 允许）；WS 路由单实例实现；**管理后台默认密钥 dev-admin-key 生产必须更换**，建议 HTTPS + IP 白名单；admin 端点暂无自动化测试（下批次补 MockMvc 切片测试） |
| 最近完整测试 | 2026-09-14（impl.4）：单测 12/12（新增 RateLimitWindowTest）、tsc 0 错误、E2E B1-B21 全 PASS。本轮修复：自定义 ObjectMapper 导致时间序列化为数字（前端会显示 1970 年）→ 显式关闭 WRITE_DATES_AS_TIMESTAMPS。impl.3 批次：三子 Agent + 独立 Review、幂等键失败补偿、201 对齐、UI 美化。此前：2026-09-13 E2E 首次全 PASS；2026-09-14 并发压测发现并修复 REPEATABLE READ 快照重试失败（join 改 READ_COMMITTED + 重试 8 次，50 抢 7 座 0 超卖） |
| 下一阶段 | 微信开发者工具按 ACCEPTANCE C 清单人工验证 → Review/QA Agent 独立检查 → 接真实微信与 OCR（需外部资源） |

## 环境与端口

`docker compose up --build`（MySQL 宿主 23306 / Redis 16379 / RabbitMQ 25672+15672 / 后端 8080；宿主 3306/5672/6379 被占用或处于 Windows 排除段）。每小时自动续跑任务已建立（读 PROJECT_STATUS → 检查环境 → 补跑验收 → 更新状态）。

## 变更规则

冻结后，任何 Agent 不得直接修改 `openapi.yaml`、`db/migration/V1__init.sql`、Ride 状态或 WebSocket 协议。发现问题时在 `docs/rfc/` 新建 RFC（已接受：RFC-001~008），由集成负责人决定是否接受。

## 遗留 P2（Review 记录，下一批次处理）

- place-picker 页已无入口（孤儿页），决定去留并从 app.json 摘除；
- IdempotencyService 两个理论边界（Error 不补偿、记录落库阶段故障键卡住）可加固；
- 压测与真实微信/OCR 依赖外部资源（见上表阻塞原因）。
