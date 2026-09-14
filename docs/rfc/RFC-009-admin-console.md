# RFC-009：管理员 Web 后台（反馈查看 / 流量统计 / 拼车与地点管理）

## 问题
用户要求提供配套管理员后台：查看反馈意见、统计流量等常用功能。普通 Web 页面即可，非小程序。

## 为什么需要修改
运营基础能力（规范 §4 admin 模块职责：地点管理/用户管理/Ride管理/内容审核）。现有管理能力只有地点 CRUD（RFC-005，X-Admin-Key 保护），反馈提交后无查看入口，无任何流量统计。

## 影响模块
- 后端新增只读/管理端点（全部在既有 `X-Admin-Key` 拦截器覆盖内 `/api/v1/admin/**`，见 WebConfig）：
  - `GET /api/v1/admin/feedback?page=&size=` 分页反馈列表（feedback 模块内 AdminFeedbackController）
  - `GET /api/v1/admin/rides?status=&page=&size=` 全量拼车单分页（ride 模块内 AdminRideController，只读）
  - `GET /api/v1/admin/stats/overview` 流量统计（admin 模块，跨模块只经各模块 application service 新增的统计方法，不碰对方 repository）
- 前端：`server/src/main/resources/static/admin/index.html` 单文件静态后台（原生 HTML/JS/CSS，无构建依赖），随后端镜像由 Nginx/Spring 静态资源直接服务。
- 不修改冻结契约（openapi 为小程序客户端契约，管理端沿用 RFC-005 先例不入 openapi）；数据库无结构变更。

## 安全说明
静态 HTML 本身公开（无数据），所有数据接口必须 `X-Admin-Key`；密钥由页面登录框输入、存 localStorage。密钥必须来自未提交 `.env` 的独立强随机值；历史默认密钥已移除，仍建议加 HTTPS + IP 白名单（记入 Known Issues）。

状态：已由 Integration 评审接受（用户 2026-09-14 指令）。
