# RFC-008：新增 GET /rides/mine 我的发布列表 + 下架能力

## 问题
用户指出：热门单满了之后仍不断有人询问，发布者没有任何管理入口，体验差。"我的"页需要能查看自己发布的所有行程并支持删除/下架。

## 为什么需要修改
现有契约只有 `GET /rides?view=PREBOOK|BOOKED`（公开列表），无法查询"我发布的"（含已结束/已取消状态）。

## 影响模块
ride（新增只读查询端点）、小程序（新增 pages/my-rides 页面 + "我的"页入口）。

## 建议修改
- 新增 `GET /api/v1/rides/mine`（登录态）：返回 ownerId=当前用户 的全部 Ride（含 CANCELLED/EXPIRED/CLOSED），按创建时间倒序。openapi.yaml 同步补充该端点（Integration 接受本 RFC 后修改，与 RFC-001 先例一致）。
- "删除"= 下架：活跃状态（PUBLISHED/MATCHING/BOOKED/IN_PROGRESS）经既有 `POST /rides/{id}/transitions` 迁移到 `CANCELLED`（状态机已允许，owner-only + 乐观锁 + 幂等键复用），下架后从所有公开列表消失，不再有人能联系。终态（CLOSED/CANCELLED/EXPIRED）不可再删，仅展示。
- 危险操作二次确认（UI_SPEC）。

状态：已由 Integration 评审接受（用户 2026-09-14 指令）。
