# 架构与编码契约

## 模块化单体

后端为 Spring Boot 3 / Java 21 模块化单体，部署在 Nginx 后；MySQL 是业务事实源，Redis 仅承担缓存、在线状态、未读数、连接路由、通知冷却，RabbitMQ 只消费 Outbox 的外围事件。模块为 `auth`、`user`、`place`、`ride`、`chat`、`notification`、`importer`、`admin`、`common`、`infrastructure`。

每个模块只暴露 application service 与 DTO；不得跨模块直接操作对方 repository/table。核心业务事务内同时写业务数据与 `outbox_event`，由可重复消费的异步消费者投递 MQ。

## 接口优先

外部依赖和可替换策略必须先定义 interface，应用层依赖接口而非 Spring/Redis/微信/OCR SDK。最低接口包括：

```java
public interface RideProviderAdapter {
    boolean supports(ImportContext context);
    RideImportResult importRide(ImportContext context);
}
public interface OcrProvider { OcrResult recognize(byte[] image); }
public interface NotificationGateway { NotificationResult send(NotificationCommand command); }
public interface OnlinePresence { boolean isInSession(long userId, long sessionId); }
```

`RideProviderFactory` 只从已注册 Adapter 中选择 `supports=true` 的实现；没有合规 Adapter 时返回“需手动填写”，不能虚构平台接口。状态迁移通过 `RideStateMachine` 接口集中校验。

## 函数式编程约定

小程序使用 TypeScript 严格模式。领域计算、字段校验、DTO 映射、状态可达性和展示文案应写为无副作用的纯函数，输入与输出采用 `readonly` interface；网络、存储、页面跳转和 WebSocket 只存在于 adapter/service 边界。禁止在页面中散落可变业务状态或把 `any` 作为领域模型。

```ts
export interface RideSummary { readonly id: string; readonly status: RideStatus; readonly remainingSeats: number }
export const remainingSeats = (memberCount: number, capacity: number): number =>
  Math.max(0, capacity - memberCount)
```

Java 侧同样优先使用不可变 record/DTO、`Optional`/Stream 处理查询结果；状态变更、持久化和发布事件保留在显式命令处理器中，避免把副作用伪装成纯函数。

## 安全与可观测性

认证身份只从 token 解析，不接受客户端传入的 ownerId/senderId。所有权限失败、状态非法跳转、幂等冲突和并发失败均返回标准错误码。敏感车牌在日志中脱敏。聊天内容保存 30 天，定时任务按 `created_at` 删除过期数据。

