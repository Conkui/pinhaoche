// 预打车列表 + 发布入口。视觉顺序：路线 > 出发时间 > 同行人数 > 其他（规范 §7）。
import * as rideApi from '../../services/ride'
import { Ride } from '../../services/ride'
import { formatTime, remainingSeats, STATUS_LABELS } from '../../utils/format'

interface PrebookCard {
  id: number
  ownerId: number
  route: string
  depart: string
  joined: number
  seats: number
  remark: string
  statusLabel: string
  /** 状态标签语义着色类（RFC-007）：仅服务于样式，不影响业务逻辑 */
  statusClass: string
  joinable: boolean
}

/** 状态 → 语义标签色：待同行=蓝 info、匹配中=绿 success，其余（已满/过期等）=中性灰 */
const STATUS_TAG_CLASS: Record<string, string> = {
  PUBLISHED: 'status-tag-info',
  MATCHING: 'status-tag-success',
}

const JOINABLE = ['PUBLISHED', 'MATCHING', 'BOOKED']

Page({
  data: {
    loading: true,
    loadFailed: false,
    cards: [] as PrebookCard[],
  },

  onShow() {
    this.reload()
  },

  onPullDownRefresh() {
    this.reload(() => wx.stopPullDownRefresh())
  },

  reload(done?: () => void) {
    getApp().ensureLogin()
      .then(() => rideApi.listRides('PREBOOK'))
      .then((rides: Ride[]) => {
        this.setData({
          loading: false,
          loadFailed: false,
          cards: rides.map((r) => ({
            id: r.id,
            ownerId: r.ownerId,
            route: `${r.origin.text} → ${r.destination.text}`,
            depart: formatTime(r.departTime),
            joined: r.memberCount,
            seats: remainingSeats(r.memberCount, r.capacity),
            remark: r.remark ?? '',
            statusLabel: STATUS_LABELS[r.status] ?? r.status,
            statusClass: STATUS_TAG_CLASS[r.status] ?? 'status-tag-neutral',
            joinable: JOINABLE.includes(r.status) && remainingSeats(r.memberCount, r.capacity) > 0,
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

  /** 卡片唯一 Primary Action：联系发布者。详情页才提供加入（UI_SPEC：每页一个 Primary）。 */
  contactOwner(e: WechatMiniprogram.TouchEvent) {
    const card = this.data.cards.find((c) => c.id === e.currentTarget.dataset.id)
    if (!card) {
      return
    }
    wx.showLoading({ title: '连接中' })
    getApp().ensureLogin()
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

  openDetail(e: WechatMiniprogram.TouchEvent) {
    wx.navigateTo({ url: `/pages/ride-detail/index?rideId=${e.currentTarget.dataset.id}` })
  },

  goPublish() {
    wx.navigateTo({ url: '/pages/publish/index' })
  },
})
