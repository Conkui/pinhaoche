// 导入"已打上"页（RFC-004 / 规范 §16-19）。
// V1 流程：无官方 API → 用户确认字段 + 手动填写（规范 §17 降级链路）。
// 车牌、地点必须用户确认后才提交；剩余距离需附更新时间说明（不能冒充实时，规范 §19）。
import { importRide, ImportRideRequest, PlaceRef } from '../../services/ride'
import { uuid } from '../../services/request'
import { parseShareText } from '../../utils/shareText'

const PROVIDERS: Array<{ value: ImportRideRequest['provider']; label: string }> = [
  { value: 'DIDI', label: '滴滴' },
  { value: 'HUAXIAOZHU', label: '花小猪' },
  { value: 'OTHER', label: '其他平台' },
]

const WAITINGS = [5, 10, 15]

Page({
  data: {
    providers: PROVIDERS,
    waitings: WAITINGS,
    providerIndex: 0,
    plateNumber: '',
    origin: null as PlaceRef | null,
    destination: null as PlaceRef | null,
    remainingDistance: '',
    waitingIndex: -1,
    seats: 3,
    submitting: false,
    shareText: '',
    parseHint: '',
  },

  onLoad() {
    const channel = this.getOpenerEventChannel()
    channel.on('placeSelected', (payload: { slot: string; place: PlaceRef }) => {
      if (payload.slot === 'origin') {
        this.setData({ origin: payload.place })
      } else if (payload.slot === 'destination') {
        this.setData({ destination: payload.place })
      }
    })
  },

  onProviderChange(e: WechatMiniprogram.PickerChange) {
    this.setData({ providerIndex: Number(e.detail.value) })
  },

  onPlate(e: WechatMiniprogram.Input) {
    this.setData({ plateNumber: e.detail.value.toUpperCase() })
  },

  pickOrigin() {
    wx.navigateTo({ url: '/pages/place-picker/index?slot=origin' })
  },

  pickDestination() {
    wx.navigateTo({ url: '/pages/place-picker/index?slot=destination' })
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
    if (d.submitting) {
      return
    }
    this.setData({ submitting: true })
    const idemKey = uuid()
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
        wx.showToast({ title: '已发布', icon: 'success' })
        setTimeout(() => wx.switchTab({ url: '/pages/booked/index' }), 600)
      })
      .catch((err: Error) => {
        this.setData({ submitting: false })
        wx.showToast({ title: err.message, icon: 'none' })
      })
  },
})
