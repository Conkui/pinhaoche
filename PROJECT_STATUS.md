# 项目状态

| 项目 | 当前值 |
| --- | --- |
| 当前版本 | V1.0.0-impl.2（CONTRACT_FREEZE_V1 已冻结，docs/CONTRACT_FREEZE_V1.md） |
| 当前阶段 | V1 验收链路端到端验证通过（ACCEPTANCE_V1 B1-B17 全 PASS）；待微信开发者工具人工检查（C 清单） |
| 数据库版本 | V2__feedback.sql（Flyway，V1 冻结 + V2 反馈表） |
| API 版本 | /api/v1（RFC-001/002/004/006 端点已实现） |
| 已完成模块 | 契约全部文档；后端 auth/user/place/ride/chat/notification/importer/feedback/common/infrastructure；小程序 10 页面 + 服务层；预打车出发时间过期过滤 + EXPIRED 定时任务；分享文本解析预填；意见反馈；docker 一键环境 |
| 尚未完成模块（含阻塞原因） | ① 真实微信订阅消息网关——需微信 appid/secret + 订阅消息模板 id + 用户授权流程，缺外部凭证；② 真实 OCR Provider——需腾讯云/兼容 OCR API 密钥；③ 聊天 30 天清理定时任务——无外部依赖，待下个实现批次（纯代码任务）；④ 小程序订阅消息授权交互（wx.requestSubscribeMessage）——依赖①的模板 id；⑤ 压测（HTTP 300 QPS / WS 5000 连接）——需独立压测环境与工具（JMeter/k6），避免与验收环境混跑 |
| 已知问题 | 滴滴/花小猪无官方第三方 API（禁止抓包），导入走"分享文本预填→用户确认→手动填写"降级（规范 §17 允许）；WS 路由单实例实现 |
| 最近完整测试 | 2026-09-13：单测 7/7、小程序 tsc 0 错误、E2E B1-B17 全 PASS（含幂等重放、3 人并发抢位、非法迁移/越权 403、过期单不展示并落 EXPIRED、WS ACK/推送/幂等重发、通知冷却 3:1）。2026-09-14 自动冒烟复检通过（登录/places/rides/401 正常，唯一 ERROR 日志为 B9 幂等测试预期路径） |
| 下一阶段 | 微信开发者工具按 ACCEPTANCE C 清单人工验证 → Review/QA Agent 独立检查 → 接真实微信与 OCR（需外部资源） |

## 环境与端口

`docker compose up --build`（MySQL 宿主 23306 / Redis 16379 / RabbitMQ 25672+15672 / 后端 8080；宿主 3306/5672/6379 被占用或处于 Windows 排除段）。每小时自动续跑任务已建立（读 PROJECT_STATUS → 检查环境 → 补跑验收 → 更新状态）。

## 变更规则

冻结后，任何 Agent 不得直接修改 `openapi.yaml`、`db/migration/V1__init.sql`、Ride 状态或 WebSocket 协议。发现问题时在 `docs/rfc/` 新建 RFC（已接受：RFC-001~006），由集成负责人决定是否接受。
