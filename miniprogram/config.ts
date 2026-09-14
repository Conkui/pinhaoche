// 全局配置：本地联调指向 docker-compose 起的后端；上线替换为 HTTPS 域名并在小程序后台配置白名单。
// 注意：必须保持单文件 config.ts——微信开发者工具的 TS 模块加载不支持目录索引导入
// （require('../config') 只解析 config.js，不回退 config/index.js，曾导致启动即白屏）。
export const BASE_URL = 'http://127.0.0.1:8080/api/v1'
export const WS_URL = 'ws://127.0.0.1:8080/ws/v1/chat'

// dev 联调固定登录 code 开关：本地后端 WECHAT_MOCK=true 时配合使用，上线前必须改为 false。
// 为什么需要：dev 下 wx.login 每次返回随机新 code，mock 网关把 code 当 openid →
// 每次重登都会 upsert 出全新用户（身份漂移，"我的发布"看不到旧单）。
// 开启后 auth.ts 改用 storage 持久化的固定 dev code，openid 稳定 = 同一个用户。
// 生产环境走真实微信 openid（天然稳定），不受此开关影响（false 时走原 wx.login 路径）。
export const DEV_MOCK_LOGIN = true
