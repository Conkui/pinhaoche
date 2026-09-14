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
- TextSanitizer 保留 \n、\t，并有意保留 0x7F(DEL)（不在控制字符剥离范围，历史数据兼容取舍，Review P2-2 记录）。

## 部署硬约束（Review P1-1，生产上线前必须落实）
登录限流按 `X-Forwarded-For` 第一段取客户端 IP，其可信前提是 **Nginx 必须用覆盖式写法**：
`proxy_set_header X-Forwarded-For $remote_addr;`（而非 `$proxy_add_x_forwarded_for` 追加式——
追加式下客户端可伪造 XFF 第一段，轮换伪造 IP 即可绕过登录 20 次/分钟限流）。
E2E/本地直连环境无 XFF 头，回退 remoteAddr，不受影响。生产 Nginx 配置落库时必须固化该写法。

状态：已由 Integration 评审接受（用户 2026-09-14 指令"一般违规请求拦截"）。实施：后端安全子 Agent + 前端登录子 Agent，独立 Review Agent 审查（结论 APPROVE_WITH_FIXES，P1-1 已补本节、P2-3 已修复，其余 P2 记录在 PROJECT_STATUS 遗留项）。
