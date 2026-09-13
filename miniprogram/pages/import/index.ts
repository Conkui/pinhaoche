// 导入"已打上"页（RFC-004 / 规范 §16-19）。
// V1 流程：无官方 API → 用户确认字段 + 手动填写（规范 §17 降级链路）。
// 车牌、地点必须用户确认后才提交；剩余距离需附更新时间说明（不能冒充实时，规范 §19）。
// 地点改为本页内联下拉（预设 + 末尾"其他"），不再跳转 place-picker 页。
// 幂等契约（规范 §21）：Idempotency-Key 只在首次提交时生成并存入 data，失败重试复用同一 key。
import { importRide, ImportRideRequest, PlaceRef } from '../../services/ride'
import { listPlaces, Place } from '../../services/place'
import { uuid } from '../../services/request'
import { parseShareText } from '../../utils/shareText'

const PROVIDERS: Array<{ value: ImportRideRequest['provider']; label: string }> = [
  { value: 'DIDI', label: '滴滴' },
  { value: 'HUAXIAOZHU', label: '花小猪' },
  { value: 'OTHER', label: '其他平台' },
]

const WAITINGS = [5, 10, 15]

// CUSTOM 地点契约：text 1..100 字（快照写入 origin_text / destination_text）
const CUSTOM_PLACE_MAX = 100

Page({
  data: {
    providers: PROVIDERS,
    waitings: WAITINGS,
    providerIndex: 0,
    plateNumber: '',
    origin: null as PlaceRef | null,
    destination: null as PlaceRef | null,

    // 内联下拉数据源：placeOptions = GET /places 预设地点名 + 末尾追加"其他"
    places: [] as Place[],
    placeOptions: [] as string[],
    placesError: false,

    // 选"其他"后展开的本页内联自定义输入（每个槽位独立开关）
    originCustomOpen: false,
    originCustomText: '',
    destinationCustomOpen: false,
    destinationCustomText: '',

    remainingDistance: '',
    waitingIndex: -1,
    seats: 3,
    submitting: false,
    shareText: '',
    parseHint: '',

    // 幂等键：首次提交生成，失败重试复用；成功后清空
    idemKey: '',
  },

  onLoad() {
    this.loadPlaces()
  },

  /** 拉取管理员预设地点（V1 禁止模糊搜索，客户端全量拉取后内联下拉展示）。 */
  loadPlaces() {
    this.setData({ placesError: false })
    listPlaces()
      .then((places) => {
        // 末尾固定追加"其他"：选中后展开本页 CUSTOM 输入框
        this.setData({
          places,
          placeOptions: places.map((p) => p.name).concat('其他'),
        })
      })
      .catch(() => {
        // 降级：预设地点拉取失败时用户仍可先填其他字段，提供重试入口
        this.setData({ placesError: true })
      })
  },

  onOriginPick(e: WechatMiniprogram.PickerChange) {
    const idx = Number(e.detail.value)
    const { places } = this.data
    // placeOptions 恒以"其他"结尾：预设为空也允许自定义（Review P2 修复，删除空列表早退守卫）
    if (idx === places.length) {
      // 选中"其他"：展开本页内联输入框，原选择清空，确认后才生效为 CUSTOM
      this.setData({ originCustomOpen: true, origin: null })
      return
    }
    const p = places[idx]
    const ref: PlaceRef = { type: 'PRESET', placeId: p.id, text: p.name }
    this.setData({ origin: ref, originCustomOpen: false })
  },

  onDestinationPick(e: WechatMiniprogram.PickerChange) {
    const idx = Number(e.detail.value)
    const { places } = this.data
    if (idx === places.length) {
      this.setData({ destinationCustomOpen: true, destination: null })
      return
    }
    const p = places[idx]
    const ref: PlaceRef = { type: 'PRESET', placeId: p.id, text: p.name }
    this.setData({ destination: ref, destinationCustomOpen: false })
  },

  onOriginCustomInput(e: WechatMiniprogram.Input) {
    this.setData({ originCustomText: e.detail.value })
  },

  onDestinationCustomInput(e: WechatMiniprogram.Input) {
    this.setData({ destinationCustomText: e.detail.value })
  },

  confirmOriginCustom() {
    const text = this.data.originCustomText.trim()
    if (!text) {
      wx.showToast({ title: '请输入地点名称', icon: 'none' })
      return
    }
    if (text.length > CUSTOM_PLACE_MAX) {
      wx.showToast({ title: `地点名称不超过${CUSTOM_PLACE_MAX}字`, icon: 'none' })
      return
    }
    // CUSTOM 契约：placeId 必须为 null，text 为用户输入快照
    this.setData({
      origin: { type: 'CUSTOM', placeId: null, text },
      originCustomOpen: false,
      originCustomText: text,
    })
  },

  confirmDestinationCustom() {
    const text = this.data.destinationCustomText.trim()
    if (!text) {
      wx.showToast({ title: '请输入地点名称', icon: 'none' })
      return
    }
    if (text.length > CUSTOM_PLACE_MAX) {
      wx.showToast({ title: `地点名称不超过${CUSTOM_PLACE_MAX}字`, icon: 'none' })
      return
    }
    this.setData({
      destination: { type: 'CUSTOM', placeId: null, text },
      destinationCustomOpen: false,
      destinationCustomText: text,
    })
  },

  onProviderChange(e: WechatMiniprogram.PickerChange) {
    this.setData({ providerIndex: Number(e.detail.value) })
  },

  onPlate(e: WechatMiniprogram.Input) {
    this.setData({ plateNumber: e.detail.value.toUpperCase() })
  },

  onDistance(e: WechatMiniprogram.Input) {
    this.setData({ remainingDistance: e.detail.value })
  },

  onWaiting(e: WechatMiniprogram.TouchEvent) {
    this.setData({ waitingIndex: Number(e.currentTarget.dataset.index) })
  },

  decSeats() {
    if (this.data.seats > 1) {
      this.setData({ seats: this.data.seats - 1 })
    }
  },

  incSeats() {
    if (this.data.seats < 8) {
      this.setData({ seats: this.data.seats + 1 })
    }
  },

  onShareTextInput(e: WechatMiniprogram.Input) {
    this.setData({ shareText: e.detail.value, parseHint: '' })
  },

  /**
   * 解析分享文本预填字段（规范 §17 优先级 2）。结果仅预填，提交仍需用户确认（规范 §18）。
   */
  parseShare() {
    const r = parseShareText(this.data.shareText)
    if (!r.provider && !r.plateNumber && r.remainingDistanceKm == null) {
      this.setData({ parseHint: '未能识别出有效信息，请手动填写' })
      return
    }
    const patch: {
      providerIndex?: number
      plateNumber?: string
      remainingDistance?: string
      parseHint: string
    } = { parseHint: '已按分享文本预填，请核对后再提交' }
    if (r.provider) {
      const idx = PROVIDERS.findIndex((p) => p.value === r.provider)
      if (idx >= 0) {
        patch.providerIndex = idx
      }
    }
    if (r.plateNumber) {
      patch.plateNumber = r.plateNumber
    }
    if (r.remainingDistanceKm != null) {
      patch.remainingDistance = String(r.remainingDistanceKm)
    }
    this.setData(patch)
  },

  onSubmit() {
    const d = this.data
    if (!d.plateNumber.trim()) {
      wx.showToast({ title: '请填写车牌号', icon: 'none' })
      return
    }
    if (!d.origin || !d.destination) {
      wx.showToast({ title: '请选择出发地和目的地', icon: 'none' })
      return
    }
    if (d.originCustomOpen || d.destinationCustomOpen) {
      wx.showToast({ title: '请先确认自定义地点', icon: 'none' })
      return
    }
    if (d.submitting) {
      // 提交中禁止重复点击（幂等保护）
      return
    }
    // 幂等：key 一次生成存 data，失败重试复用同一 key；成功后清空
    const idemKey = d.idemKey || uuid()
    this.setData({ submitting: true, idemKey })
    const distance = d.remainingDistance.trim() ? Number(d.remainingDistance) : null
    const req: ImportRideRequest = {
      provider: PROVIDERS[d.providerIndex].value,
      plateNumber: d.plateNumber.trim(),
      origin: d.origin,
      destination: d.destination,
      remainingDistance: distance != null && !Number.isNaN(distance) ? distance : null,
      waitingMinutes: d.waitingIndex >= 0 ? WAITINGS[d.waitingIndex] : null,
      seats: d.seats,
    }
    importRide(req, idemKey)
      .then(() => {
        this.setData({ idemKey: '' })
        wx.showToast({ title: '已发布', icon: 'success' })
        setTimeout(() => wx.switchTab({ url: '/pages/booked/index' }), 600)
      })
      .catch((err: Error) => {
        // 失败保留 idemKey，用户重试仍用同一 key，服务端幂等去重
        this.setData({ submitting: false })
        wx.showToast({ title: err.message, icon: 'none' })
      })
  },
})
