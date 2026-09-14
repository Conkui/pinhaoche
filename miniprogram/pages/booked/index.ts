// 实时拼（原"已打上"）列表（首页）。视觉顺序：出发地→目的地 / 立即联系 / 余位 / 车牌 / 剩余距离（UI_SPEC）。
import * as rideApi from '../../services/ride'
import { Ride } from '../../services/ride'
import { formatDistance, remainingSeats, waitingText } from '../../utils/format'
import { getRealtimeCarpoolAvailability, RealtimeCarpoolAvailability } from '../../services/feature'

interface BookedCard {
  id: number
  ownerId: number
  route: string
  seats: number
  plate: string
  distance: string
  waiting: string
  status: string
}

Page({
  data: {
    loading: true,
    loadFailed: false,
    /** 实时拼关闭时，整页切换为公告，防止缓存中的已打上车订单继续展示。 */
    realtimeEnabled: true,
    announcement: '',
    cards: [] as BookedCard[],
  },

  onShow() {
    this.reload()
  },

  onPullDownRefresh() {
    this.reload(() => wx.stopPullDownRefresh())
  },

  reload(done?: () => void) {
    const app = getApp()
    app.ensureLogin()
      .then(() => getRealtimeCarpoolAvailability())
      .then((availability: RealtimeCarpoolAvailability) => {
        if (!availability.enabled) {
          this.setData({
            loading: false,
            loadFailed: false,
            realtimeEnabled: false,
            announcement: availability.announcement,
            cards: [],
          })
          done?.()
          return null
        }
        this.setData({ realtimeEnabled: true, announcement: '' })
        return rideApi.listRides('BOOKED')
      })
      .then((rides: Ride[] | null) => {
        // 已展示停用公告时，不可用空列表覆盖该状态。
        if (rides === null) {
          return
        }
        this.setData({
          loading: false,
          loadFailed: false,
          cards: rides.map((r) => ({
            id: r.id,
            ownerId: r.ownerId,
            route: `${r.origin.text} → ${r.destination.text}`,
            seats: remainingSeats(r.memberCount, r.capacity),
            plate: r.plateNumber ?? '',
            distance: formatDistance(r.remainingDistance, r.distanceUpdatedAt),
            waiting: waitingText(r.waitingMinutes),
            status: r.status,
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

  /** Primary Action：立即联系车主 → 复用/创建会话 → 聊天页（规范 §10）。 */
  contactOwner(e: WechatMiniprogram.TouchEvent) {
    const card = this.data.cards.find((c) => c.id === e.currentTarget.dataset.id)
    if (!card) {
      return
    }
    const app = getApp()
    wx.showLoading({ title: '连接中' })
    app.ensureLogin()
      .then(() => require('../../services/chat').ensureSession(card.id, card.ownerId))
      .then((session: { id: number; peerUserId: number }) => {
        wx.hideLoading()
        wx.navigateTo({ url: `/pages/chat/index?sessionId=${session.id}&peerUserId=${session.peerUserId}` })
      })
      .catch((err: Error) => {
        wx.hideLoading()
        wx.showToast({ title: err.message, icon: 'none' })
      })
  },

  goImport() {
    if (!this.data.realtimeEnabled) {
      return
    }
    // 入口每次实时读取运营开关：缓存页面不能绕过后台刚刚发布的停用公告。
    getRealtimeCarpoolAvailability()
      .then((feature) => {
        if (!feature.enabled) {
          wx.showModal({ title: '实时拼暂不可用', content: feature.announcement, showCancel: false })
          return
        }
        wx.navigateTo({ url: '/pages/import/index' })
      })
      .catch((err: Error) => wx.showToast({ title: err.message || '暂无法检查功能状态', icon: 'none' }))
  },
})
