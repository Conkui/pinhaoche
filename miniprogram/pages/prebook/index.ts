// 预打车列表 + 发布入口。视觉顺序：路线 > 出发时间 > 同行人数 > 其他（规范 §7）。
import * as rideApi from '../../services/ride'
import { Ride } from '../../services/ride'
import * as featureApi from '../../services/feature'
import { RealtimeCarpoolAvailability } from '../../services/feature'
import { listPlaces, Place } from '../../services/place'
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

const OTHER_PLACE_OPTION = '其他输入'

/** 把当前本地筛选后的快照转换为可展示卡片；不改写 Ride 的地点快照。 */
const buildCards = (rides: readonly Ride[]): PrebookCard[] => rides
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
  .filter((card) => card.joinable)

Page({
  data: {
    loading: true,
    loadFailed: false,
    /** 运营关闭时不请求/渲染拼车单，改为单一公告态，避免旧缓存造成误导。 */
    realtimeEnabled: true,
    announcement: '',
    /** 服务端原始列表仅作本页只读快照；筛选变化不重复请求接口。 */
    allRides: [] as Ride[],
    cards: [] as PrebookCard[],
    places: [] as Place[],
    /** 下拉首项是重置，末项展开本页自定义输入。 */
    placeOptions: ['不限地点', OTHER_PLACE_OPTION] as string[],
    placesLoading: true,
    placesError: false,
    originFilterText: '',
    destinationFilterText: '',
    originCustomOpen: false,
    destinationCustomOpen: false,
    originCustomDraft: '',
    destinationCustomDraft: '',
    /** 空态文案：区分两种"空"——没人发布 / 有单但全部不可加入（被过滤） */
    emptyTitle: '还没有人发布预打车',
    emptySub: '点下方按钮发布第一条',
  },

  onShow() {
    this.reload()
  },

  onLoad() {
    this.loadPlaces()
  },

  /** 预设地点仅辅助筛选；拉取失败时仍保留自定义输入这一降级能力。 */
  loadPlaces() {
    this.setData({ placesLoading: true, placesError: false })
    listPlaces()
      .then((places) => this.setData({
        places,
        placeOptions: ['不限地点'].concat(places.map((place) => place.name), OTHER_PLACE_OPTION),
        placesLoading: false,
      }))
      .catch(() => this.setData({ placesLoading: false, placesError: true }))
  },

  /** 在当前已拉取的不可变路线快照上重算卡片，出发与目的地条件组合为 AND。 */
  applyRouteFilter() {
    const { allRides, originFilterText, destinationFilterText } = this.data
    const routeMatched = rideApi.filterRidesByRoute(allRides, { originText: originFilterText, destinationText: destinationFilterText })
    const cards = buildCards(routeMatched)
    const hasRouteFilter = Boolean(originFilterText || destinationFilterText)
    this.setData({
      cards,
      emptyTitle: allRides.length === 0
        ? '还没有人发布预打车'
        : routeMatched.length === 0 && hasRouteFilter
          ? '暂无符合路线的拼车单'
          : '暂无可加入的拼车单',
      emptySub: allRides.length === 0
        ? '点下方按钮发布第一条'
        : routeMatched.length === 0 && hasRouteFilter
          ? '可修改出发地或目的地筛选'
          : '已满或不可加入的拼车单已隐藏',
    })
  },

  onOriginPick(e: WechatMiniprogram.PickerChange) {
    const index = Number(e.detail.value)
    const { places } = this.data
    if (index === 0) {
      this.setData({ originFilterText: '', originCustomOpen: false, originCustomDraft: '' })
      this.applyRouteFilter()
      return
    }
    if (index === places.length + 1) {
      this.setData({ originCustomOpen: true })
      return
    }
    this.setData({ originFilterText: places[index - 1].name, originCustomOpen: false, originCustomDraft: '' })
    this.applyRouteFilter()
  },

  onDestinationPick(e: WechatMiniprogram.PickerChange) {
    const index = Number(e.detail.value)
    const { places } = this.data
    if (index === 0) {
      this.setData({ destinationFilterText: '', destinationCustomOpen: false, destinationCustomDraft: '' })
      this.applyRouteFilter()
      return
    }
    if (index === places.length + 1) {
      this.setData({ destinationCustomOpen: true })
      return
    }
    this.setData({ destinationFilterText: places[index - 1].name, destinationCustomOpen: false, destinationCustomDraft: '' })
    this.applyRouteFilter()
  },

  onOriginCustomInput(e: WechatMiniprogram.Input) {
    this.setData({ originCustomDraft: e.detail.value })
  },

  onDestinationCustomInput(e: WechatMiniprogram.Input) {
    this.setData({ destinationCustomDraft: e.detail.value })
  },

  confirmOriginCustom() {
    this.setData({ originFilterText: this.data.originCustomDraft.trim(), originCustomOpen: false })
    this.applyRouteFilter()
  },

  confirmDestinationCustom() {
    this.setData({ destinationFilterText: this.data.destinationCustomDraft.trim(), destinationCustomOpen: false })
    this.applyRouteFilter()
  },

  /** 同时清空两端条件，恢复已拉取的完整可加入列表。 */
  resetRouteFilter() {
    this.setData({
      originFilterText: '', destinationFilterText: '',
      originCustomOpen: false, destinationCustomOpen: false,
      originCustomDraft: '', destinationCustomDraft: '',
    })
    this.applyRouteFilter()
  },

  onPullDownRefresh() {
    this.reload(() => wx.stopPullDownRefresh())
  },

  reload(done?: () => void) {
    getApp().ensureLogin()
      .then(() => featureApi.getRealtimeCarpoolAvailability())
      .then((availability: RealtimeCarpoolAvailability) => {
        if (!availability.enabled) {
          this.setData({
            loading: false,
            loadFailed: false,
            realtimeEnabled: false,
            announcement: availability.announcement,
            allRides: [],
            cards: [],
          })
          done?.()
          return null
        }
        this.setData({ realtimeEnabled: true, announcement: '' })
        return rideApi.listRides('PREBOOK')
      })
      .then((rides: Ride[] | null) => {
        // 开关关闭时上一段已完成渲染；不能把 null 当作空列表覆盖公告页。
        if (rides === null) {
          return
        }
        this.setData({
          loading: false,
          loadFailed: false,
          allRides: rides,
        })
        this.applyRouteFilter()
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
    if (!this.data.realtimeEnabled) {
      return
    }
    wx.navigateTo({ url: '/pages/publish/index' })
  },
})
