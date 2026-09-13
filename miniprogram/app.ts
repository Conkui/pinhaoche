// app.ts：全局登录态管理。V1 只有微信静默登录（wx.login code → /auth/login 换 token）。
import { login } from './services/auth'

interface UserInfo {
  id: number
  nickname: string
  avatar?: string
}

App({
  globalData: {
    token: '' as string,
    user: null as UserInfo | null,
  },

  onLaunch() {
    const token = wx.getStorageSync('phc_token') as string
    const user = wx.getStorageSync('phc_user') as UserInfo | null
    if (token) {
      this.globalData.token = token
      this.globalData.user = user
      return
    }
    this.ensureLogin()
  },

  /** 登录失败静默重试一次；仍然失败时让具体页面提示重试（UI_SPEC：失败可重试）。 */
  ensureLogin(): Promise<UserInfo> {
    if (this.globalData.token && this.globalData.user) {
      return Promise.resolve(this.globalData.user)
    }
    return login().then((res) => {
      this.globalData.token = res.token
      this.globalData.user = res.user
      wx.setStorageSync('phc_token', res.token)
      wx.setStorageSync('phc_user', res.user)
      return res.user
    })
  },
})

export {}
