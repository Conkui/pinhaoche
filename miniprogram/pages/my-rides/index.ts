// 我的发布页（RFC-008）：展示当前用户发布的全部行程（含终态），
// 活跃状态提供"下架"（= 迁移到 CANCELLED，状态机 owner-only + 乐观锁）。
import * as rideApi from '../../services/ride'
import { Ride, RideStatus } from '../../services/ride'
import { uuid } from '../../services/request'
import { formatTime, remainingSeats, STATUS_LABELS } from '../../utils/format'

interface MyRideCard {
  id: number
  route: string
  depart: string
  members: string
  plate: string
  version: number
  status: RideStatus
  statusLabel: string
  tagClass: string
  active: boolean
  canceling: boolean
}

// 活跃状态才允许下架（状态机：PUBLISHED/MATCHING/BOOKED/IN_PROGRESS → CANCELLED）
const ACTIVE_STATUSES: RideStatus[] = ['PUBLISHED', 'MATCHING', 'BOOKED', 'IN_PROGRESS']

/** 状态 → 标签配色：蓝=待同行/匹配中，绿=已打上，橙=行程中，其余（终态）灰。 */
function tagClass(status: RideStatus): string {
  if (status === 'PUBLISHED' || status === 'MATCHING') {
    return 'tag-info'
  }
  if (status === 'BOOKED') {
    return 'tag-success'
  }
  if (status === 'IN_PROGRESS') {
    return 'tag-warn'
  }
  return 'tag-muted'
}

function toCard(r: Ride): MyRideCard {
  return {
    id: r.id,
    route: `${r.origin.text} → ${r.destination.text}`,
    depart: formatTime(r.departTime),
    members: `已有 ${r.memberCount} 人 · 剩 ${remainingSeats(r.memberCount, r.capacity)} 位`,
    plate: r.plateNumber ?? '',
    version: r.version,
    status: r.status,
    statusLabel: STATUS_LABELS[r.status] ?? r.status,
    tagClass: tagClass(r.status),
    active: ACTIVE_STATUSES.indexOf(r.status) >= 0,
    canceling: false,
  }
}

Page({
  data: {
    loading: true,
    loadFailed: false,
    cards: [] as MyRideCard[],
  },

  onShow() {
    this.reload()
  },

  onPullDownRefresh() {
    this.reload(() => wx.stopPullDownRefresh())
  },

  reload(done?: () => void) {
    getApp().ensureLogin()
      .then(() => rideApi.listMine())
      .then((rides: Ride[]) => {
        this.setData({
          loading: false,
          loadFailed: false,
          cards: rides.map(toCard),
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

  // 幂等键按每条记录持有：下架失败重试必须复用同一个 key，
  // 否则服务端会把重试当成新请求（规范 §21 Idempotency-Key 契约）。
  cancelKeys: {} as Record<number, string>,

  /** 下架入口：危险操作，先 wx.showModal 二次确认（UI_SPEC）。 */
  onTakeDown(e: WechatMiniprogram.TouchEvent) {
    const id = Number(e.currentTarget.dataset.id)
    const card = this.data.cards.find((c) => c.id === id)
    if (!card || card.canceling || !card.active) {
      return
    }
    wx.showModal({
      title: '下架行程',
      content: '下架后行程将从列表中移除，其他人无法再联系你。确认下架？',
      confirmText: '确认',
      confirmColor: '#ff4d4f',
      success: (res) => {
        if (!res.confirm) {
          return
        }
        this.transitCancel(id)
      },
    })
  },

  /** 下架 = CANCELLED 迁移：透传 version 做乐观锁，409 时重拉列表取最新版本。 */
  transitCancel(id: number) {
    const card = this.data.cards.find((c) => c.id === id)
    if (!card) {
      return
    }
    if (!this.cancelKeys[id]) {
      this.cancelKeys[id] = uuid()
    }
    this.setCardCanceling(id, true)
    rideApi.transitRide(id, 'CANCELLED', card.version, this.cancelKeys[id])
      .then(() => {
        // 成功才清空幂等键；本地直接把该项刷为已取消，不重拉全量列表
        delete this.cancelKeys[id]
        const idx = this.data.cards.findIndex((c) => c.id === id)
        if (idx < 0) {
          return
        }
        this.setData({
          [`cards[${idx}].status`]: 'CANCELLED',
          [`cards[${idx}].statusLabel`]: STATUS_LABELS.CANCELLED,
          [`cards[${idx}].tagClass`]: tagClass('CANCELLED'),
          [`cards[${idx}].active`]: false,
          [`cards[${idx}].canceling`]: false,
        })
        wx.showToast({ title: '已下架', icon: 'success' })
      })
      .catch((err: Error) => {
        this.setCardCanceling(id, false)
        // 版本冲突（409）说明远端状态已变化：重拉列表拿最新 version；幂等键保留供重试复用
        this.reload()
        wx.showToast({ title: err.message, icon: 'none' })
      })
  },

  setCardCanceling(id: number, canceling: boolean) {
    const idx = this.data.cards.findIndex((c) => c.id === id)
    if (idx < 0) {
      return
    }
    // 操作进行中禁点：卡片按钮置灰并吞掉重复点击
    this.setData({ [`cards[${idx}].canceling`]: canceling })
  },

  openDetail(e: WechatMiniprogram.TouchEvent) {
    wx.navigateTo({ url: `/pages/ride-detail/index?rideId=${e.currentTarget.dataset.id}` })
  },
})
