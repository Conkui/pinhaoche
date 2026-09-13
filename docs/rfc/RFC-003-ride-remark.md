# RFC-003：Ride 响应补充 remark 字段

## 问题
`CreateRideRequest` 接受 `remark` 且 UI 规范要求预打车卡片展示备注，但 `Ride` 响应 schema 没有 `remark`，前端无法展示。

## 为什么需要修改
数据库 `ride.remark` 已存在，仅响应 DTO 缺字段。

## 影响模块
ride、小程序列表页。

## 建议修改
`Ride` schema 增加可空 `remark: string`，不影响既有必填字段。
