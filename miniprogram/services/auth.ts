// 登录服务：wx.login code → POST /auth/login（RFC-002）。
import { BASE_URL } from '../config'

export interface LoginResponse {
  token: string
  user: { id: number; nickname: string; avatar?: string }
}

export function login(): Promise<LoginResponse> {
  return new Promise((resolve, reject) => {
    wx.login({
      success: (res) => {
        if (!res.code) {
          reject(new Error('wx.login 未返回 code'))
          return
        }
        wx.request({
          url: `${BASE_URL}/auth/login`,
          method: 'POST',
          data: { code: res.code },
          success: (r) => {
            if (r.statusCode === 200) {
              resolve(r.data as LoginResponse)
            } else {
              reject(new Error(`登录失败(${r.statusCode})`))
            }
          },
          fail: () => reject(new Error('登录网络异常')),
        })
      },
      fail: () => reject(new Error('wx.login 调用失败')),
    })
  })
}
