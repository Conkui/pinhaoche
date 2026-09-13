# 拼好车微信小程序 — AI 实现规范 V1

## 1. 项目目标

开发微信小程序 **“拼好车”**，面向偏僻学校学生拼车出行。

V1 只解决两类核心场景：

```text
预打车
用户提前发布路线和时间
→ 找到同行者
→ 聊天沟通
→ 最终共同叫车

已打上
用户已经叫到车
→ 发布车辆和路线
→ 其他用户立即联系
→ 车主收到微信提醒
→ 决定是否等待同行者
```

整个项目必须优先保证核心链路短、稳定、真实可上线，不追求无意义的大而全功能。

---

# 2. 强制产品边界

## 2.1 不使用地图和定位

V1：

- 不申请 GPS。
- 不接地图 SDK。
- 不根据坐标搜索地点。
- 不进行附近地点推荐。
- 不做地点模糊匹配。

---

## 2.2 地点只有两种来源

### 管理员预设

管理员后台可以：

```text
新增
修改
排序
启用
停用
```

例如：

```text
学校东门
学校西门
火车站
高铁站
机场
汽车站
```

管理员新增后，小程序无需发版即可显示。

### 用户自定义

用户可以直接填写：

```text
XX宿舍楼下
万达广场2号门
XX医院南门
```

自定义地点仅属于当前拼车记录，不自动加入公共地点库。

---

# 3. V1 技术栈

避免过度微服务化。

采用：

```text
微信原生小程序
TypeScript
WeUI

Spring Boot 3.x
Java 21

MySQL 8
Redis 7
RabbitMQ
WebSocket

Nginx
Docker
Flyway

腾讯云/兼容 OCR 服务
微信订阅消息
```

架构：

```text
             微信小程序
                 │
          HTTPS / WebSocket
                 │
              Nginx
                 │
        Spring Boot Application
                 │
 ┌───────────────┼─────────────────┐
 │               │                 │
MySQL           Redis           RabbitMQ
 │                                 │
业务数据                        异步事件
```

采用：

> **模块化单体 Modular Monolith**

暂时不要拆微服务。

模块之间保持清晰边界，使未来可以独立拆分。

---

# 4. 后端模块

```text
server/
├── auth
├── user
├── place
├── ride
├── chat
├── notification
├── importer
├── admin
├── common
└── infrastructure
```

模块职责：

### auth

负责：

```text
微信登录
Token
身份认证
权限检查
```

### place

负责：

```text
管理员预设地点
地点排序
启停
地点缓存
```

禁止加入模糊搜索系统。

### ride

负责：

```text
预打车
已打上
成员申请
人数
生命周期
```

### chat

负责：

```text
会话
聊天消息
WebSocket
未读消息
消息ACK
30天记录
```

### notification

负责：

```text
微信订阅消息
消息防骚扰
通知降级
```

### importer

负责：

```text
滴滴
花小猪
截图OCR
分享文本
第三方适配
```

### admin

负责：

```text
地点管理
用户管理
Ride管理
内容审核
运营基础能力
```

---

# 5. 核心数据模型

## User

```text
id
openid
nickname
avatar
status
created_at
updated_at
```

---

## Place

```text
id
name
sort_order
enabled
created_at
updated_at
```

只代表管理员维护的公共地点。

---

## Ride

```text
id
owner_id

origin_type
origin_place_id
origin_text

destination_type
destination_place_id
destination_text

depart_time

member_count
capacity

status

plate_number
provider

remaining_distance
distance_updated_at

waiting_minutes

version

created_at
updated_at
```

地点类型：

```text
PRESET
CUSTOM
```

即使使用 PRESET，也必须把地点名字保存到：

```text
origin_text
destination_text
```

形成快照。

---

# 6. Ride 状态机

统一使用一个 Ride 聚合。

状态：

```text
DRAFT
 ↓
PUBLISHED
 ↓
MATCHING
 ↓
BOOKED
 ↓
IN_PROGRESS
 ↓
ARRIVED
 ↓
CLOSED
```

异常状态：

```text
CANCELLED
EXPIRED
```

页面映射：

```text
PUBLISHED
MATCHING
    ↓
预打车

BOOKED
    ↓
已打上
```

`BOOKED` 表示：

> 已经叫到车，但是车主仍愿意等待拼车者。

车主点击：

```text
不再等人
```

后：

```text
BOOKED
→
IN_PROGRESS
```

随后从“已打上可拼车列表”移除。

---

# 7. 预打车

发布字段：

```text
出发地
目的地
出发时间
当前同行人数
最大人数
备注
```

地点选择：

```text
请选择地点

常用地点
[学校东门]
[学校西门]
[高铁站]
[机场]

+ 自定义填写
```

列表重点：

```text
学校东门 → 高铁站

今天 18:30

已有 2 人
还可加入 2 人

[联系发布者]
```

视觉优先级：

```text
路线
>
时间
>
同行人数
>
其他信息
```

---

# 8. 已打上

这是 V1 最重要的页面之一。

用户首先必须看到：

```text
从哪里出发
去哪里
还有没有位置
能否马上联系车主
```

卡片建议：

```text
学校东门
      ↓
高铁站

还有 2 个位置

苏A·12345

距目的地约 18.6 km
2分钟前更新

[立即联系车主]
```

优先级：

```text
出发地 / 目的地
↓
立即联系
↓
剩余位置
↓
车牌
↓
剩余距离
```

不要把车牌作为卡片最大元素。

---

# 9. 等待时间

发布“已打上”时允许车主选择：

```text
愿意等待：

5分钟
10分钟
15分钟
时间不确定
```

用于帮助拼车者判断是否值得联系。

不做自动倒计时强制关闭。

---

# 10. 聊天

用户点击：

```text
立即联系车主
```

流程：

```text
检查是否已有 ChatSession
        ↓
有 → 复用
无 → 创建
        ↓
进入聊天
```

提供快捷文本：

```text
还有位置吗？

我也去这个地方，可以等我一下吗？

我马上到出发点。
```

但底层全部作为普通 TEXT 消息保存。

---

# 11. WebSocket 消息协议

发送：

```json
{
  "clientMsgId": "uuid",
  "sessionId": 10001,
  "type": "TEXT",
  "content": "我马上到",
  "timestamp": 0
}
```

服务端成功落库后返回 ACK。

必须保证：

```text
客户端发送
↓
数据库落库
↓
ACK
↓
推送对方
```

禁止：

```text
先推送
后保存
```

---

# 12. 消息幂等

每条客户端消息生成：

```text
client_msg_id
```

数据库唯一索引：

```text
(sender_id, client_msg_id)
```

网络重试不得生成重复消息。

---

# 13. 聊天历史

保存：

```text
最近30天
```

每日定时清理超过30天的数据。

数据库索引至少包括：

```text
(session_id, id)
(created_at)
```

WebSocket 断线重新连接后，通过：

```text
lastMessageId
```

增量补消息。

Redis 只负责：

```text
在线状态
未读数
连接路由
通知冷却
```

聊天内容最终以 MySQL 为准。

---

# 14. 微信级提醒

核心目标：

> 拼车人联系已经叫到车的用户后，即使车主没有打开小程序，也尽可能知道有人想拼车。

实现：

```text
发送消息
 ↓
Message落库
 ↓
判断接收者是否在当前聊天页面
 ↓
是
 └─ WebSocket

否
 └─ 微信订阅消息
```

必须使用微信允许的订阅消息机制。

不得假设服务端可以无限主动发送微信通知。

---

# 15. 通知防骚扰

不能：

```text
10条聊天
→
10条微信提醒
```

Redis：

```text
notify:{sessionId}:{receiverId}
```

第一次产生未读：

```text
发送微信提醒
```

之后进入：

```text
5分钟冷却
```

期间只增加未读数量。

用户进入聊天并阅读后：

```text
未读清零
```

下一轮才允许再次通知。

---

# 16. 已打上导入

V1 支持：

```text
滴滴
花小猪
```

必须设计统一接口：

```java
public interface RideProviderAdapter {

    boolean supports(ImportContext context);

    RideImportResult importRide(ImportContext context);

}
```

实现：

```text
DidiRideProviderAdapter
HuaxiaozhuRideProviderAdapter
```

创建：

```text
RideProviderFactory
```

负责根据来源返回 Adapter。

---

# 17. 导入优先级

严格按照：

```text
官方 API
    ↓
分享文本 / 分享信息
    ↓
截图 OCR
    ↓
用户确认
    ↓
手动填写
```

禁止：

```text
抓包
Hook
破解签名
模拟登录
读取App私有数据
伪造第三方API
```

没有官方接口时必须明确采用 OCR / 用户确认降级方案。

---

# 18. OCR

处理：

```text
截图
 ↓
OCR
 ↓
平台识别
 ↓
字段解析
 ↓
置信度
 ↓
用户确认
```

目标字段：

```text
车牌号
出发地
目的地
订单状态
剩余距离
预计到达时间
平台
```

置信度低于阈值的字段必须让用户确认。

不能自动提交错误信息。

---

# 19. 剩余距离

如果第三方正式 API 可以获得：

```text
同步更新
```

如果只能截图识别：

必须显示：

```text
距目的地约 18.6km

更新于 18:32
```

不能冒充实时数据。

---

# 20. 并发人数控制

禁止：

```text
SELECT member_count
↓
Java判断
↓
UPDATE
```

使用：

```sql
UPDATE ride
SET member_count = member_count + 1,
    version = version + 1
WHERE id = ?
  AND member_count < capacity
  AND version = ?;
```

受影响行为：

```text
1 → 成功
0 → 已满或发生并发竞争
```

---

# 21. HTTP 幂等

以下接口必须有幂等机制：

```text
发布Ride
加入Ride
确认成员
取消
第三方导入
```

客户端生成：

```text
Idempotency-Key
```

结合：

```text
Redis SETNX
+
数据库唯一约束
```

处理。

---

# 22. MQ

MQ 只处理外围任务：

```text
微信通知
缓存失效
操作日志
OCR异步任务
```

核心业务不能依赖 MQ 才能完成。

使用：

```text
Outbox Pattern
```

业务事务：

```text
修改Ride
+
写outbox_event
```

必须在同一数据库事务内完成。

之后异步发送 MQ。

消费者全部必须支持重复消费。

---

# 23. 设计模式

仅在合适位置使用，不为模式而模式。

### State Pattern

Ride 状态迁移。

### Responsibility Chain

用于：

```text
发布Ride校验
加入Ride校验
第三方导入处理
```

### Factory + Adapter

用于：

```text
滴滴
花小猪
未来其他平台
```

### Strategy

用于：

```text
OCR识别策略
通知策略
```

### Domain Event

用于：

```text
RideBooked
RideMemberJoined
MessageReceived
RideClosed
```

---

# 24. UI 规范

必须参考：

```text
Apple Human Interface Guidelines
+
微信 WeUI
```

禁止 Coding Agent 自由发挥设计。

总体风格：

```text
白色卡片
浅灰背景
充足留白
清晰层级
低装饰
大标题
统一圆角
系统蓝作为主要动作色
```

底栏：

```text
已打上
预打车
消息
我的
```

一个页面只保留一个最明显 Primary Action。

禁止：

```text
大面积渐变
霓虹色
复杂阴影
花哨动画
大量悬浮组件
过度卡片化
```

---

# 25. 中文注释

所有关键实现必须有中文注释。

特别是：

```text
并发
幂等
MQ
Outbox
缓存一致性
状态机
WebSocket
订阅消息
第三方Adapter
OCR降级
权限
```

注释重点解释：

```text
为什么这么实现
解决什么问题
什么情况下可能失败
如何降级
```

不要写：

```java
// 设置用户名
user.setUsername(username);
```

这种无意义注释。

---

# 26. 开发方式：禁止单 Agent 长时间连续开发

这是整个项目的强制开发规则。

不要：

```text
给一个Agent完整需求
↓
让它写几万行代码
↓
让它自己改接口
↓
让它自己测试
↓
让它自己宣布完成
```

长上下文后非常容易产生：

```text
需求遗忘
接口漂移
重复实现
虚构已经完成的代码
偷偷改变数据库
TODO冒充完成
测试结果幻觉
```

因此必须采用：

> **短任务 Agent + 固定契约 + 独立 Review**

---

# 27. 第一阶段：Spec Agent

首先只允许一个 Agent 做设计，不写业务代码。

产出：

```text
docs/PRODUCT_SPEC.md
docs/ARCHITECTURE.md
docs/STATE_MACHINE.md
docs/WEBSOCKET_PROTOCOL.md
docs/UI_SPEC.md

openapi.yaml

db/migration/V1__init.sql
```

然后冻结。

定义：

```text
API
DTO
数据库
状态机
WebSocket协议
错误码
```

完成以后进入：

```text
CONTRACT_FREEZE_V1
```

---

# 28. 契约冻结

其他 Agent：

禁止自行修改：

```text
openapi.yaml
数据库核心字段
WebSocket协议
Ride状态
公共DTO
```

发现设计问题时创建：

```text
docs/rfc/RFC-XXX.md
```

内容：

```text
发现的问题
为什么需要修改
影响模块
建议修改
```

由 Integration Agent 决定是否修改。

禁止某个 Agent 为了方便自己的代码偷偷修改公共结构。

---

# 29. 第二阶段：并行开发

分为多个短 Agent。

## Agent A — Place

只负责：

```text
管理员地点CRUD
地点缓存
小程序地点接口
```

不得处理 Ride。

---

## Agent B — Ride Core

只负责：

```text
Ride
状态机
发布
取消
加入
人数并发
幂等
```

不得处理聊天。

---

## Agent C — Chat

只负责：

```text
ChatSession
ChatMessage
WebSocket
ACK
未读
断线恢复
30天记录
```

不得实现微信订阅通知。

---

## Agent D — Notification

只负责：

```text
微信订阅消息
冷却机制
在线状态判断
通知事件消费
```

---

## Agent E — Import

只负责：

```text
RideProviderAdapter
Factory
DidiAdapter
HuaxiaozhuAdapter
OCR Provider
OCR解析
```

---

## Agent F — MiniProgram / Ride

只实现：

```text
已打上
预打车
地点选择
Ride详情
```

---

## Agent G — MiniProgram / Chat

只实现：

```text
消息列表
聊天页面
WebSocket
ACK
断线重连
订阅消息授权交互
```

---

# 30. 每个 Agent 的输入必须很小

每个 Agent 只给：

```text
对应模块需求
相关OpenAPI
相关数据库表
依赖接口
验收测试
```

不要重新把整个项目几十页文档全部塞给每个 Agent。

例如 Chat Agent 只需要：

```text
Chat需求
Chat表
WebSocket协议
User接口
Ride ID
```

减少上下文污染。

---

# 31. 每个 Agent 必须交付

统一要求：

```text
源码
测试
README
变更说明
未完成项
风险说明
```

必须明确写：

```text
Completed
Not Completed
Known Issues
```

禁止 Agent 用自然语言声称：

> 已经全部完成。

必须由测试判断。

---

# 32. 第三阶段：Review Agent

开发 Agent 不负责最终验收自己的代码。

单独启动：

```text
Backend Review Agent
Frontend Review Agent
Security Review Agent
```

Review Agent 不新增功能。

只检查：

```text
契约是否一致
是否越权修改
是否存在TODO
是否存在Mock冒充生产逻辑
是否正确处理并发
幂等是否真的有效
SQL索引
异常处理
中文注释
测试
```

---

# 33. 第四阶段：Integration Agent

Integration Agent 不重新设计功能。

只负责：

```text
合并代码
解决冲突
运行迁移
启动完整环境
端到端联调
```

启动：

```text
MySQL
Redis
RabbitMQ
Backend
MiniProgram Mock
```

完成整条流程测试。

---

# 34. 第五阶段：QA Agent

最终独立 Agent。

必须测试：

### Ride

```text
重复发布
重复加入
最后一个位置并发竞争
取消Ride
状态非法跳转
```

### Chat

```text
重复消息
断线
重连
消息补偿
多端消息
30天数据
```

### Notification

```text
在线不发送微信提醒
离线触发提醒
5分钟防重复通知
拒绝订阅时正常聊天
```

### Import

```text
正确截图
模糊截图
OCR失败
未知平台
低置信度
手动修改
```

### Security

```text
修改别人Ride
读取别人Chat
伪造用户ID
恶意文本
接口刷请求
```

---

# 35. 压测

至少：

```text
HTTP 300 QPS
WebSocket 5000连接
```

重点测试：

```text
Ride列表
地点列表
聊天发送
聊天连接
成员加入
```

不是要求所有接口都跑到300 QPS。

---

# 36. AI 项目状态管理

项目根目录维护：

```text
PROJECT_STATUS.md
```

内容固定：

```text
当前版本
已经完成的模块
尚未完成模块
当前数据库版本
当前API版本
已知问题
最近一次完整测试结果
下一阶段任务
```

每一个 Agent 开始前先读取。

完成后只能更新自己负责的部分。

这样新的 Agent 不依赖上一 Agent 的“聊天记忆”。

---

# 37. 禁止将聊天上下文作为项目事实来源

AI 必须遵守：

> 代码库中的文件才是事实来源。

优先级：

```text
源码
>
OpenAPI
>
数据库Migration
>
项目文档
>
PROJECT_STATUS
>
Agent对话描述
```

如果 Agent 说：

> 我之前已经实现了 XXX。

但代码库不存在：

```text
视为没有实现。
```

---

# 38. Git 策略

每个 Agent 独立：

```text
branch
或
git worktree
```

例如：

```text
feature/place
feature/ride-core
feature/chat
feature/notification
feature/importer
feature/miniprogram-ride
feature/miniprogram-chat
```

禁止多个 Agent 同时直接修改 main。

---

# 39. V1 最终验收链路

必须真实跑通：

```text
用户A微信登录
 ↓
选择预设地点 / 自定义地点
 ↓
发布预打车
 ↓
用户B看到
 ↓
联系A
 ↓
双方聊天
 ↓
确认同行
 ↓
A叫车
 ↓
滴滴/花小猪信息导入
 ↓
Ride进入“已打上”
 ↓
首页醒目显示：
出发地 → 目的地
 ↓
用户C发现
 ↓
点击立即联系车主
 ↓
A收到微信级提醒
 ↓
双方沟通
 ↓
C加入
 ↓
A点击“不再等人”
 ↓
Ride离开可拼车列表
```

这条链路优先级高于所有附加功能。

---

# 40. AI 开发最高原则

所有 Agent 必须遵守：

```text
不擅自增加需求。

不擅自修改产品核心逻辑。

不虚构第三方接口。

不把Mock当生产功能。

不允许TODO代替关键实现。

不因为“企业级”而盲目拆微服务。

不为了使用设计模式而制造复杂度。

每个核心写操作考虑幂等。

每个人数变化考虑并发。

数据库结构只能通过Flyway修改。

第三方能力全部做Adapter。

核心业务必须有自动化测试。

关键代码必须写中文注释。

UI不得脱离iOS HIG和WeUI自行发挥。

发现公共设计问题必须通过RFC处理。

开发Agent不能自己给自己做最终验收。

代码库，而不是Agent记忆，是唯一事实来源。
```