# RFC-004：新增 POST /rides/import 导入"已打上"

## 问题
"已打上" ride（BOOKED，含车牌/平台/剩余距离）来源于滴滴/花小猪导入 + 用户确认，但契约只有 `POST /rides`（创建预打车），导入结果无法落库。

## 为什么需要修改
验收链路"A 叫车 → 信息导入 → Ride 进入已打上"无法实现。

## 影响模块
importer、ride、小程序导入页。

## 建议修改
新增 `POST /api/v1/rides/import`（带 `Idempotency-Key`），请求 `{ provider, plateNumber, origin: PlaceRef, destination: PlaceRef, remainingDistance?, waitingMinutes? }`。服务端经 `RideProviderFactory` 选择 Adapter；无合规 Adapter 时按契约降级为"用户确认字段 + 手动填写"，直接创建 `status=BOOKED` 的 Ride。不做任何抓包/破解（规范 §17）。
