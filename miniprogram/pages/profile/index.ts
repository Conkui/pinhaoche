// "我的"页：登录信息 + 关于。V1 不做资料编辑（Not Completed）。
const app = getApp()

Page({
  data: {
    user: null as { id: number; nickname: string; avatar?: string } | null,
    version: 'V1.0.0',
  },

  onShow() {
    app.ensureLogin().then((user: { id: number; nickname: string; avatar?: string }) => {
      this.setData({ user })
    }).catch(() => {
      wx.showToast({ title: '登录失败，下拉重试', icon: 'none' })
    })
  },

  onPullDownRefresh() {
    wx.stopPullDownRefresh()
    this.onShow()
  },

  goFeedback() {
    wx.navigateTo({ url: '/pages/feedback/index' })
  },

  relogin() {
    wx.removeStorageSync('phc_token')
    wx.removeStorageSync('phc_user')
    app.globalData.token = ''
    app.globalData.user = null
    this.onShow()
  },
})
