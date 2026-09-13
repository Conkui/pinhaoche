# RFC-002：新增 POST /auth/login 微信登录

## 问题
所有接口与 WebSocket 握手都需要身份 token，但契约中没有登录接口，token 无获取途径。

## 为什么需要修改
认证是链路第 1 步（用户微信登录）。

## 影响模块
auth、user、全部需鉴权接口、小程序。

## 建议修改
新增 `POST /api/v1/auth/login`，请求 `{ code }`（wx.login 的 code），服务端经 WeChatGateway（接口化，可 Mock）换 openid，upsert app_user 后签发 token。响应 `LoginResponse { token, user: { id, nickname, avatar } }`。token 传输使用 `Authorization: Bearer`，WebSocket 握手使用 `?token=`。
