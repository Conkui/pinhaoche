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
    /** 空态文案：区分两种"空"——没人发布 / 有单但全部不可加入（被过滤） */
    emptyTitle: '还没有人发布预打车',
    emptySub: '点下方按钮发布第一条',
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
        // 用户红线（2026-09-14）"不能拼的不要显示"：joinable=false（已满/状态不可加入）
        // 的单在数据层直接过滤、完全不渲染，而不是 wxml 里隐藏——避免渲染无意义节点，
        // 也保证 contactOwner/openDetail 只可能命中可加入的单。
        // 注意：过滤会产生第二种空态（有单但全被过滤），emptyTitle/emptySub 在 ts 侧
        // 按"原始列表是否为空"区分，wxml 不重复判断。
        const cards = rides
          .map((r) => ({
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
          }))
          .filter((c) => c.joinable)
        this.setData({
          loading: false,
          loadFailed: false,
          cards,
          emptyTitle: rides.length === 0 ? '还没有人发布预打车' : '暂无可加入的拼车单',
          emptySub: rides.length === 0 ? '点下方按钮发布第一条' : '已满或不可加入的拼车单已隐藏',
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

  /** 联系发布者：次级小按钮（用户 2026-09-14 弱化），仅负责建会话跳聊天；
   *  本页唯一 Primary Action 是悬浮吸底的"发布预打车"（UI_SPEC：每页一个 Primary）。 */
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
