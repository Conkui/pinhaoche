# 项目状态

| 项目 | 当前值 |
| --- | --- |
| 当前版本 | V1.0.0-impl.4（CONTRACT_FREEZE_V1 + RFC-001~010；git 基线 f81ee8d） |
| 当前阶段 | 管理员 Web 后台（RFC-009：统计/反馈/拼车/地点，http://localhost:8080/admin/index.html）+ 接口防刷限流与输入净化（RFC-010）已交付；独立 Review 通过（P2 已处理）、E2E B1-B21 全绿 |
| 数据库版本 | V2__feedback.sql（Flyway，V1 冻结 + V2 反馈表） |
| API 版本 | /api/v1（RFC-001/002/004/006 端点已实现） |
| 已完成模块 | 契约全部文档；后端 auth/user/place/ride/chat/notification/importer/feedback/common/infrastructure；小程序 10 页面 + 服务层；预打车出发时间过期过滤 + EXPIRED 定时任务；分享文本解析预填；意见反馈；docker 一键环境 |
| 尚未完成模块（含阻塞原因） | ① 真实微信订阅消息网关——需微信 appid/secret + 订阅消息模板 id + 用户授权流程，缺外部凭证；② 真实 OCR Provider——需腾讯云/兼容 OCR API 密钥；③ 聊天 30 天清理定时任务——无外部依赖，待下个实现批次（纯代码任务）；④ 小程序订阅消息授权交互（wx.requestSubscribeMessage）——依赖①的模板 id；⑤ 压测（HTTP 300 QPS / WS 5000 连接）——需独立压测环境与工具（JMeter/k6），避免与验收环境混跑 |
| 已知问题 | 滴滴/花小猪无官方第三方 API（禁止抓包），导入走"分享文本预填→用户确认→手动填写"降级（规范 §17 允许）；WS 路由单实例实现；**管理后台默认密钥 dev-admin-key 生产必须更换**，建议 HTTPS + IP 白名单；admin 端点暂无自动化测试（下批次补 MockMvc 切片测试）；**登录限流依赖 Nginx 覆盖式 XFF 配置（Review P1-1，见 RFC-010 部署硬约束，生产落库前必须落实）** |
| Review 遗留 P2（安全批次，APPROVE_WITH_FIXES） | P2-1 限流 catch RuntimeException 范围偏大（与 IdempotencyService 既有约定一致，建议收窄 DataAccessException）；P2-2 TextSanitizer 有意保留 0x7F（RFC-010 已补说明）；P2-3 反馈净化后空串兜底已加（实际被 @NotBlank trim 语义前置拦截，属纵深防御）；P2-4 TextSanitizer 单测待补；P2-5 前端 Promise.finally 低版本基础库兼容待开发者工具验证；P2-6 NOT_FOUND/UNAUTHORIZED 未入 openapi ApiError 枚举（下个 RFC 对齐） |
| 最近完整测试 | 2026-09-14（Review 修复后回归）：单测 12/12、E2E B1-B17 全 PASS（12 PASS/0 FAIL）、限流 25 连发=20×200+5×429 复验、反馈净化链路 400/204 验证。impl.4：单测 12/12（新增 RateLimitWindowTest）、tsc 0 错误、E2E B1-B21 全 PASS，修复时间序列化为数字缺陷（关闭 WRITE_DATES_AS_TIMESTAMPS）。impl.3：三子 Agent + 独立 Review、幂等键失败补偿、201 对齐、UI 美化。此前：并发压测发现并修复 REPEATABLE READ 快照重试失败（join 改 READ_COMMITTED + 重试 8 次，50 抢 7 座 0 超卖） |
| 下一阶段 | 微信开发者工具按 ACCEPTANCE C 清单人工验证 → Review/QA Agent 独立检查 → 接真实微信与 OCR（需外部资源） |

## 环境与端口

`docker compose up --build`（MySQL 宿主 23306 / Redis 16379 / RabbitMQ 25672+15672 / 后端 8080；宿主 3306/5672/6379 被占用或处于 Windows 排除段）。每小时自动续跑任务已建立（读 PROJECT_STATUS → 检查环境 → 补跑验收 → 更新状态）。

## 变更规则

冻结后，任何 Agent 不得直接修改 `openapi.yaml`、`db/migration/V1__init.sql`、Ride 状态或 WebSocket 协议。发现问题时在 `docs/rfc/` 新建 RFC（已接受：RFC-001~008），由集成负责人决定是否接受。

## 遗留 P2（Review 记录，下一批次处理）

- place-picker 页已无入口（孤儿页），决定去留并从 app.json 摘除；
- IdempotencyService 两个理论边界（Error 不补偿、记录落库阶段故障键卡住）可加固；
- 压测与真实微信/OCR 依赖外部资源（见上表阻塞原因）。
