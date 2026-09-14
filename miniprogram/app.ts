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

  /**
   * 登录失败静默重试一次；仍然失败时让具体页面提示重试（UI_SPEC：失败可重试）。
   * force=true：先清空内存与 storage 里的旧凭证再重新登录——供 request.ts 在收到
   * 业务 401（token 过期）后调用；不加 force 时旧 token 会让短路检查直接返回
   * 过期登录态，起不到刷新作用。默认 false，页面无感调用行为不变。
   */
  ensureLogin(force = false): Promise<UserInfo> {
    if (force) {
      this.globalData.token = ''
      this.globalData.user = null
      try {
        wx.removeStorageSync('phc_token')
        wx.removeStorageSync('phc_user')
      } catch (e) {
        // storage 清理失败不阻塞登录流程：登录成功后会全量覆盖写回
      }
    }
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
