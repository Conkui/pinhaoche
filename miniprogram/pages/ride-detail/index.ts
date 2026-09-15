// Ride 详情页：按状态显示"加入"或"不再等人"等唯一 Primary Action（UI_SPEC）。
// 取消等危险操作需二次确认（UI_SPEC）。
import * as rideApi from '../../services/ride'
import { MemberContact, Ride } from '../../services/ride'
import { uuid } from '../../services/request'
import { SERVER_ORIGIN } from '../../config'
import { absoluteUrl, formatDistance, formatTime, remainingSeats, waitingText, STATUS_LABELS } from '../../utils/format'

const JOINABLE = ['PUBLISHED', 'MATCHING', 'BOOKED']

Page({
  data: {
    rideId: 0,
    loading: true,
    loadFailed: false,
    ride: null as Ride | null,
    isMine: false,
    joinable: false,
    statusLabel: '',
    route: '',
    depart: '',
    seats: 0,
    distance: '',
    waiting: '',
    joining: false,
    canceling: false,
    memberAvatarUrls: {} as Record<number, string>,
  },

  onLoad(query: Record<string, string>) {
    this.setData({ rideId: Number(query.rideId) })
    this.reload()
  },

  onPullDownRefresh() {
    this.reload(() => wx.stopPullDownRefresh())
  },

  reload(done?: () => void) {
    const app = getApp()
    app.ensureLogin()
      .then(() => rideApi.getRide(this.data.rideId))
      .then((ride: Ride) => {
        const userId = app.globalData.user?.id
        this.applyRide(ride, userId ?? 0)
        if (ride.ownerId !== userId) {
          done?.()
          return null
        }
        return rideApi.listMemberContacts(ride.id)
      })
      .then((memberContacts: MemberContact[] | null) => {
        if (memberContacts === null) {
          return
        }
        const ride = this.data.ride
        if (ride) {
          this.setData({ ride: { ...ride, memberContacts } })
        }
        done?.()
      })
      .catch(() => {
        this.setData({ loading: false, loadFailed: true })
        done?.()
      })
  },

  applyRide(ride: Ride, userId: number) {
    const seats = remainingSeats(ride.memberCount, ride.capacity)
    this.setData({
      loading: false,
      loadFailed: false,
      ride,
      isMine: ride.ownerId === userId,
      joinable: JOINABLE.includes(ride.status) && seats > 0 && ride.ownerId !== userId,
      statusLabel: STATUS_LABELS[ride.status] ?? ride.status,
      route: `${ride.origin.text} → ${ride.destination.text}`,
      depart: formatTime(ride.departTime),
      seats,
      distance: formatDistance(ride.remainingDistance, ride.distanceUpdatedAt),
      waiting: waitingText(ride.waitingMinutes),
      // 后端只存相对头像路径；小程序 image 必须按当前服务端 origin 拼成绝对地址。
      memberAvatarUrls: Object.fromEntries((ride.memberContacts ?? []).map((member) => [
        member.userId, absoluteUrl(member.avatar, SERVER_ORIGIN),
      ])),
    })
  },

  retry() {
    this.setData({ loading: true, loadFailed: false })
    this.reload()
  },

  /** 加入：失败重试复用同一 Idempotency-Key（本页持有）。 */
  joinIdemKey: '',
  onJoin() {
    if (this.data.joining) {
      return
    }
    if (!this.joinIdemKey) {
      this.joinIdemKey = uuid()
    }
    this.setData({ joining: true })
    rideApi.joinRide(this.data.rideId, this.joinIdemKey)
      .then((ride: Ride) => {
        wx.showToast({ title: '已加入', icon: 'success' })
        this.joinIdemKey = ''
        this.applyRide(ride, getApp().globalData.user?.id ?? 0)
      })
      .catch((err: Error) => {
        this.setData({ joining: false })
        // 容量满/状态变化后刷新最新状态
        if (err.message.indexOf('满') >= 0 || err.message.indexOf('状态') >= 0) {
          this.reload()
        }
        if (err.message.indexOf('最多加入 3 个拼车') >= 0) {
          wx.showModal({ title: '暂不能加入', content: err.message, showCancel: false })
          return
        }
        wx.showToast({ title: err.message, icon: 'none' })
      })
  },

  cancelIdemKey: '',
  /** 车主"不再等人"：BOOKED → IN_PROGRESS（从可拼车列表移除）。危险操作二次确认。 */
  onStopWaiting() {
    this.confirmTransit('IN_PROGRESS', '不再等人',
      '确认后行程将离开可拼车列表，其他人无法再联系你。')
  },

  /** 车主"已到达"：IN_PROGRESS → ARRIVED。 */
  onArrive() {
    this.confirmTransit('ARRIVED', '已到达目的地', '确认行程已到达？')
  },

  /** 统一迁移：二次确认（UI_SPEC 危险操作）+ 幂等键复用 + 乐观锁版本透传。 */
  confirmTransit(target: 'IN_PROGRESS' | 'ARRIVED' | 'CANCELLED', title: string, content: string) {
    wx.showModal({
      title,
      content,
      confirmText: '确认',
      success: (res) => {
        const ride = this.data.ride
        if (!res.confirm || !ride) {
          return
        }
        if (!this.cancelIdemKey) {
          this.cancelIdemKey = uuid()
        }
        rideApi.transitRide(ride.id, target, ride.version, this.cancelIdemKey)
          .then((updated: Ride) => {
            this.cancelIdemKey = ''
            this.applyRide(updated, getApp().globalData.user?.id ?? 0)
            wx.showToast({ title: '已更新', icon: 'success' })
          })
          .catch((err: Error) => {
            // 版本冲突时拉最新状态展示
            this.reload()
            wx.showToast({ title: err.message, icon: 'none' })
          })
      },
    })
  },

  /** 联系发布者（非 owner 的次要入口，聊天路径与列表页一致）。 */
  contactOwner() {
    const ride = this.data.ride
    if (!ride) {
      return
    }
    wx.showLoading({ title: '连接中' })
    require('../../services/chat').ensureSession(ride.id, ride.ownerId)
      .then((session: { id: number; peerUserId: number }) => {
        wx.hideLoading()
        wx.navigateTo({ url: `/pages/chat/index?sessionId=${session.id}&peerUserId=${session.peerUserId}` })
      })
      .catch((err: Error) => {
        wx.hideLoading()
        wx.showToast({ title: err.message, icon: 'none' })
      })
  },

  /** 车主只可对本订单服务端返回的成员建会话，后端会按 rideId + peerUserId 创建或复用。 */
  contactMember(e: WechatMiniprogram.TouchEvent) {
    const ride = this.data.ride
    const memberId = Number(e.currentTarget.dataset.memberId)
    if (!ride || !this.data.isMine || !ride.memberContacts?.some((member) => member.userId === memberId)) {
      return
    }
    wx.showLoading({ title: '连接中' })
    require('../../services/chat').ensureSession(ride.id, memberId)
      .then((session: { id: number; peerUserId: number }) => {
        wx.hideLoading()
        wx.navigateTo({ url: `/pages/chat/index?sessionId=${session.id}&peerUserId=${session.peerUserId}` })
      })
      .catch((err: Error) => {
        wx.hideLoading()
        wx.showToast({ title: err.message, icon: 'none' })
      })
  },
})
