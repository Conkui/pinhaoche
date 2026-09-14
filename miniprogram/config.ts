// 全局配置：mock/真实两套环境由 MODE 一个开关派生，避免"改地址时漏改一处"。
// 注意：必须保持单文件 config.ts——微信开发者工具的 TS 模块加载不支持目录索引导入
// （require('../config') 只解析 config.js，不回退 config/index.js，曾导致启动即白屏）。

/**
 * 运行模式单点开关：只改这一行即可在 mock 与真实之间切换（默认 'mock'，保证误入真实模式不可能）。
 *
 * - 'mock'（本地联调，默认）：配合后端 `WECHAT_MOCK=true` 使用。auth.ts 不发真实 wx.login，
 *   改用 storage 持久化的固定 dev code，后端 mock 网关把 code 当 openid → 身份稳定，
 *   重启 Redis 也不会 upsert 出新用户（详见 DEV_MOCK_LOGIN 注释）。
 * - 'real'（真实上线/真机体验）：auth.ts 走 wx.login 拿真实 code，后端必须
 *   `WECHAT_MOCK=false` 且配好真实 appid/secret，否则 code 换不到 openid → 登录直接失败。
 *
 * 为什么收敛成一个开关：BASE_URL/WS_URL/DEV_MOCK_LOGIN 三者必须同进同退——
 * 只改地址忘了关 dev code，会拿 dev code 去请求真实后端（登录 401）；只关 dev code 忘了改地址，
 * 会拿真实 code 打本地 mock 后端（同样是 401）。派生写法让这类"半切换"在结构上不可能出现。
 */
export const MODE: 'mock' | 'real' = 'real'

/**
 * 服务端 origin（不含 /api/v1 路径）。两个用途：
 * 1) 派生 BASE_URL / WS_URL（下方）；
 * 2) 把后端返回的头像**相对路径**（RFC-011：`app_user.avatar` 存 `/files/xxx.jpg`）
 *    拼成 <image src> 可加载的绝对 URL（见 utils/format.ts 的 absoluteUrl）。
 *
 * 为什么库里只存相对路径：换域名/加 CDN 时只改这一行，不必回刷数据库。
 * 真实上线时改成 HTTPS 域名（小程序要求 request/socket 均走 https/wss 且域名已加入后台白名单）。
 */
export const SERVER_ORIGIN = 'http://127.0.0.1:8080'

export const BASE_URL = `${SERVER_ORIGIN}/api/v1`

// WS 由同一 origin 派生（http→ws、https→wss）：避免 http/ws 两处各写一遍导致协议或端口漂移，
// 漂移的表现是聊天能发消息但收不到对方消息（socket 静默连不上），排查成本高。
export const WS_URL = `${SERVER_ORIGIN.replace(/^http/, 'ws')}/ws/v1/chat`

// dev 联调固定登录 code 开关（随 MODE 自动派生，不再手改）：
// 为什么需要：dev 下 wx.login 每次返回随机新 code，mock 网关把 code 当 openid →
// 每次重登都会 upsert 出全新用户（身份漂移，"我的发布"看不到旧单）。
// 开启后 auth.ts 改用 storage 持久化的固定 dev code，openid 稳定 = 同一个用户。
// MODE='real' 时为 false：走真实微信 openid（天然稳定），不受此开关影响。
const isMockMode = (mode: 'mock' | 'real'): boolean => mode === 'mock'
export const DEV_MOCK_LOGIN = isMockMode(MODE)
