// app.ts：全局登录态管理 + 全局异常捕获（onError / onUnhandledRejection / onPageNotFound）。
// V1 只有微信静默登录（wx.login code → /auth/login 换 token）。
import { login } from './services/auth'
import { logger } from './utils/logger'

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

  /**
   * 全局脚本异常捕获：页面/服务未 catch 的异常统一落到这里，由 logger 记录
   * （本地精简摘要 + 静默上报关键日志），不影响小程序其余部分继续运行。
   * 一般性异常不再需要页面层层 try/catch，从源头减少控制台爆红。
   */
  onError(err) {
    logger.error('app.onError', err)
  },

  /**
   * 未处理的 Promise 拒绝（异步链漏 catch 是"控制台频繁爆红"的主要来源）。
   * 与 onError 同口径记录；仅记录不提示用户——未捕获拒绝多数不影响主流程。
   */
  onUnhandledRejection(res) {
    logger.error('unhandledRejection', res.reason)
  },

  /**
   * 页面不存在兜底（如分享卡片指向已下线页面）：不处理时微信会推入原生
   * "页面不存在"死胡同页。这里 switchTab 回首页 tab（pages/booked/index 在
   * app.json tabBar 中声明，必然存在；tab 页必须用 switchTab，navigateTo 会失败），
   * 保证用户至少能继续使用核心功能而不是白屏。记 warn 不记 error：
   * 属可恢复的导航问题而非代码崩溃，不上报以降噪。
   */
  onPageNotFound(res) {
    logger.warn('app.onPageNotFound', `页面不存在: ${res.path}`, {
      query: res.query,
      isEntryPage: res.isEntryPage,
    })
    wx.switchTab({
      url: '/pages/booked/index',
      fail: () => {}, // 首页 tab 必然存在；fail 留空，兜底本身绝不制造新一轮爆红
    })
  },
})

export {}
