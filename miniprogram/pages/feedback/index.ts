// 意见反馈页：入口在"我的"页，样式克制不抢主功能位置（用户指令 + UI_SPEC）。
import { submitFeedback } from '../../services/feedback'

Page({
  data: {
    content: '',
    submitting: false,
  },

  onInput(e: WechatMiniprogram.Input) {
    this.setData({ content: e.detail.value })
  },

  onSubmit() {
    const text = this.data.content.trim()
    if (!text) {
      wx.showToast({ title: '请填写反馈内容', icon: 'none' })
      return
    }
    if (this.data.submitting) {
      return
    }
    this.setData({ submitting: true })
    getApp().ensureLogin()
      .then(() => submitFeedback(text))
      .then(() => {
        wx.showToast({ title: '已提交，感谢反馈', icon: 'success' })
        this.setData({ content: '', submitting: false })
        setTimeout(() => wx.navigateBack(), 600)
      })
      .catch((err: Error) => {
        this.setData({ submitting: false })
        wx.showToast({ title: err.message, icon: 'none' })
      })
  },
})
