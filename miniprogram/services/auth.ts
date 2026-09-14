// 登录服务：wx.login code → POST /auth/login（RFC-002）。
import { BASE_URL, DEV_MOCK_LOGIN } from '../config'
import { uuid } from './request'

export interface LoginResponse {
  token: string
  user: { id: number; nickname: string; avatar?: string }
}

// dev 固定 code 的 storage key（与 phc_token/phc_user 同级，只影响 dev 联调身份）
const DEV_CODE_KEY = 'phc_dev_code'

/**
 * 获取登录 code。
 *
 * 为什么 dev 下不走 wx.login：本地后端 WECHAT_MOCK=true 时，wx.login 每次返回随机新 code，
 * mock 网关直接把 code 当 openid → Redis 重启（docker compose down/up）导致本地 token 失效，
 * request.ts 的 401 自动重登触发后每次都会 upsert 出全新用户（身份漂移，"我的发布"看不到旧单）。
 * 因此 DEV_MOCK_LOGIN=true 时改用 storage 持久化的固定 dev code：不存在则生成一次 uuid 存入，
 * 之后恒定复用 → openid 稳定 = 同一个用户。真实微信 openid 天然稳定，无此问题，
 * 故 DEV_MOCK_LOGIN=false（生产）保持原 wx.login 路径不变。
 */
function obtainCode(): Promise<string> {
  if (DEV_MOCK_LOGIN) {
    // 同步读写 storage：首次生成后立即落盘，避免并发重登（如 401 重试）各自生成不同 code
    const cached = wx.getStorageSync(DEV_CODE_KEY)
    const existing = typeof cached === 'string' && cached.length > 0 ? cached : ''
    if (existing) {
      return Promise.resolve(existing)
    }
    const generated = uuid()
    try {
      wx.setStorageSync(DEV_CODE_KEY, generated)
    } catch (e) {
      // storage 写入失败（极端情况）：本次登录仍用生成值完成，代价是下次重登会换新身份（dev 可接受），
      // 但绝不能让 login() 同步抛出打断页面 Promise 链（Review P2）
    }
    return Promise.resolve(generated)
  }
  return new Promise((resolve, reject) => {
    wx.login({
      success: (res) => {
        if (!res.code) {
          reject(new Error('wx.login 未返回 code'))
          return
        }
        resolve(res.code)
      },
      fail: () => reject(new Error('wx.login 调用失败')),
    })
  })
}

export function login(): Promise<LoginResponse> {
  return obtainCode().then(
    (code) =>
      new Promise<LoginResponse>((resolve, reject) => {
        wx.request({
          url: `${BASE_URL}/auth/login`,
          method: 'POST',
          data: { code },
          success: (r) => {
            if (r.statusCode === 200) {
              resolve(r.data as LoginResponse)
            } else {
              reject(new Error(`登录失败(${r.statusCode})`))
            }
          },
          fail: () => reject(new Error('登录网络异常')),
        })
      })
  )
}
