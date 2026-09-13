// 消息列表页（RFC-001 GET /chat-sessions）。
import { ChatSessionSummary, listSessions } from '../../services/chat'
import { formatTime } from '../../utils/format'

interface SessionCard {
  id: number
  rideId: number
  peerUserId: number
  title: string
  preview: string
  time: string
  unread: number
}

Page({
  data: {
    loading: true,
    loadFailed: false,
    sessions: [] as SessionCard[],
  },

  onShow() {
    getApp().ensureLogin().then(() => this.reload())
  },

  onPullDownRefresh() {
    this.reload(() => wx.stopPullDownRefresh())
  },

  reload(done?: () => void) {
    listSessions()
      .then((list: ChatSessionSummary[]) => {
        this.setData({
          loading: false,
          loadFailed: false,
          sessions: list.map((s) => ({
            id: s.id,
            rideId: s.rideId,
            peerUserId: s.peerUserId,
            title: `拼车对话 #${s.rideId}`,
            preview: s.lastMessagePreview ?? '暂无消息',
            time: s.lastMessageAt ? formatTime(s.lastMessageAt) : '',
            unread: s.unreadCount,
          })),
        })
        done?.()
      })
      .catch(() => {
        this.setData({ loading: false, loadFailed: true })
        done?.()
      })
  },

  retry() {
    this.setData({ loading: true, loadFailed: false })
    this.reload()
  },

  openChat(e: WechatMiniprogram.TouchEvent) {
    const s = this.data.sessions.find((x) => x.id === e.currentTarget.dataset.id)
    if (!s) {
      return
    }
    wx.navigateTo({ url: `/pages/chat/index?sessionId=${s.id}&peerUserId=${s.peerUserId}` })
  },
})
