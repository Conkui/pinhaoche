// 全局配置（Web 版，RFC-016）。与小程序 config.ts 同角色：所有环境相关值收敛到一个文件。
//
// Web 与小程序的关键差异：前端由 Spring Boot 静态资源同源托管（http://host:8080/），
// 默认直接用当前页面 origin 访问 API/WS——不存在跨域，也不需要用户改任何地址。
// 仅当前端被单独用静态服务器打开（与后端不同源）时，才需要在页面加载前设置：
//   <script>window.PHC_CONFIG = { serverOrigin: 'http://127.0.0.1:8080' }</script>
// 为什么用 window.PHC_CONFIG 而不是改本文件：与小程序「必须保持单文件 config」同理，
// 环境差异只允许出现在注入点，避免提交带个人环境地址的配置。

/** 服务端 origin（不含 /api/v1 路径）。默认同源；允许 window.PHC_CONFIG.serverOrigin 覆盖。 */
export const SERVER_ORIGIN = (window.PHC_CONFIG && window.PHC_CONFIG.serverOrigin) || window.location.origin

export const BASE_URL = `${SERVER_ORIGIN}/api/v1`

// WS 由同一 origin 派生（http→ws、https→wss）：与小程序 config.ts 相同的防漂移写法，
// 避免聊天能发消息却收不到对方消息（socket 协议/端口写错）这类排查成本高的问题。
export const WS_URL = `${SERVER_ORIGIN.replace(/^http/, 'ws')}/ws/v1/chat`

// localStorage 键集中定义：request/auth/app 三处共用，改名必须来这里（防止散落字符串漂移）。
export const STORAGE_KEYS = Object.freeze({
  token: 'phc_token',
  user: 'phc_user',
  // Web 无法调用 wx.login（RFC-016）：mock 登录用持久化固定 code 换稳定身份，
  // 与小程序 DEV_MOCK_LOGIN 的 phc_dev_code 同语义。
  devCode: 'phc_dev_code',
})

/**
 * 头像 base64 发送前拦截阈值（与小程序 user.ts 同语义）：后端上限是解码后 2 MiB 原始字节，
 * base64 长度 ≈ 原始字节 × 4/3，2 MiB → 约 2796204 字符；取 2.8e6 留余量，
 * 在发送前拦掉必然 400 的超大图，省一次弱网白等。
 */
export const MAX_AVATAR_BASE64_LENGTH = 2.8e6
