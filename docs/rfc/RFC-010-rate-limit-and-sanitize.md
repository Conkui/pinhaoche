# RFC-010：接口防刷限流 + 输入净化（安全加固）

## 问题
用户要求拦截一般违规请求。当前只有业务校验（参数长度/越权 403/状态机/幂等），没有请求频率限制（规范 §34 QA 明确含"接口刷请求"），文本入库前无控制字符净化。

## 为什么需要修改
防刷是上线红线；恶意脚本高频调用会造成资源耗尽与骚扰（聊天/反馈灌水）。

## 影响模块
- `common.security`（新增）：`RateLimitService`（Redis 固定窗口）+ 两个拦截器。
  - 登录接口 `POST /api/v1/auth/login`：按 IP 限流，20 次/分钟（防撞库/刷 code）。
  - 其余 `/api/v1/**`：按登录用户限流，120 次/分钟（在 AuthInterceptor 之后执行，用 token 解析出的 userId，不接受客户端自报）。
  - Redis 不可用时**放行并告警**（可用性优先，DB 唯一键等兜底仍生效）。
- `common.api`：`ErrorCode` 新增 `RATE_LIMITED`；超限返回 HTTP 429 + `{code:"RATE_LIMITED", message}`。openapi `ApiError.code` 枚举同步增加（小程序端需识别 429，属客户端契约，经本 RFC 接受后修改）。
- `common.util.TextSanitizer`（新增）：剥离控制字符（保留换行/制表），trim；应用于聊天消息内容与意见反馈内容入库前。
- 小程序：`request.ts` 收到 401 时单飞（single-flight）自动重新登录并重试原请求一次，避免 token 72h 过期后用户手动操作。

## 安全说明
- 限流键只依赖服务端身份（token 解析）或连接 IP，不接受请求参数指定。
- 固定窗口实现简单、Redis 两命令完成；边界突刺对 V1 可接受。
- 登录失败次数限制（撞库）V1 由 IP 限流覆盖，登录本身无密码。

状态：已由 Integration 评审接受（用户 2026-09-14 指令"一般违规请求拦截"）。实施：后端安全子 Agent + 前端登录子 Agent，独立 Review Agent 审查。
