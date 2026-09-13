// 发布预打车页。地点经 place-picker（EventChannel）选择；发布是唯一 Primary Action（UI_SPEC）。
// 失败重试复用同一 Idempotency-Key（幂等契约，规范 §21）。
import { createRide, CreateRideRequest, PlaceRef } from '../../services/ride'
import { uuid } from '../../services/request'

Page({
  data: {
    origin: null as PlaceRef | null,
    destination: null as PlaceRef | null,
    departDate: '',
    departTime: '18:00',
    memberCount: 1,
    capacity: 4,
    remark: '',
    submitting: false,
    minDate: '',
  },

  onLoad() {
    const now = new Date()
    const pad = (n: number) => `${n}`.padStart(2, '0')
    const today = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
    this.setData({ departDate: today, minDate: today })

    // 接收 place-picker 回传的地点（发布页 ←→ 地点选择页 双向通道）
    const channel = this.getOpenerEventChannel()
    channel.on('placeSelected', (payload: { slot: string; place: PlaceRef }) => {
      if (payload.slot === 'origin') {
        this.setData({ origin: payload.place })
      } else if (payload.slot === 'destination') {
        this.setData({ destination: payload.place })
      }
    })
  },

  pickOrigin() {
    wx.navigateTo({ url: '/pages/place-picker/index?slot=origin&current=' + encodeURIComponent(this.data.origin?.text ?? '') })
  },

  pickDestination() {
    wx.navigateTo({ url: '/pages/place-picker/index?slot=destination&current=' + encodeURIComponent(this.data.destination?.text ?? '') })
  },

  onDateChange(e: WechatMiniprogram.PickerChange) {
    this.setData({ departDate: String(e.detail.value) })
  },

  onTimeChange(e: WechatMiniprogram.PickerChange) {
    this.setData({ departTime: String(e.detail.value) })
  },

  incMember() {
    if (this.data.memberCount < this.data.capacity) {
      this.setData({ memberCount: this.data.memberCount + 1 })
    }
  },

  decMember() {
    if (this.data.memberCount > 1) {
      this.setData({ memberCount: this.data.memberCount - 1 })
    }
  },

  incCapacity() {
    if (this.data.capacity < 8) {
      this.setData({
        capacity: this.data.capacity + 1,
        memberCount: Math.min(this.data.memberCount, this.data.capacity + 1),
      })
    }
  },

  decCapacity() {
    if (this.data.capacity > 2) {
      this.setData({
        capacity: this.data.capacity - 1,
        memberCount: Math.min(this.data.memberCount, this.data.capacity - 1),
      })
    }
  },

  onRemark(e: WechatMiniprogram.Input) {
    this.setData({ remark: e.detail.value })
  },

  onSubmit() {
    const d = this.data
    if (!d.origin || !d.destination) {
      wx.showToast({ title: '请选择出发地和目的地', icon: 'none' })
      return
    }
    if (d.submitting) {
      return
    }
    this.setData({ submitting: true })
    const idemKey = uuid()
    // 本地时间组合 → ISO（服务端 UTC 存储，客户端转换时区）
    const departIso = new Date(`${d.departDate}T${d.departTime}:00`).toISOString()
    const req: CreateRideRequest = {
      origin: d.origin,
      destination: d.destination,
      departTime: departIso,
      memberCount: d.memberCount,
      capacity: d.capacity,
      remark: d.remark || undefined,
    }
    createRide(req, idemKey)
      .then(() => {
        wx.showToast({ title: '发布成功', icon: 'success' })
        setTimeout(() => wx.navigateBack(), 600)
      })
      .catch((err: Error) => {
        this.setData({ submitting: false })
        wx.showToast({ title: err.message, icon: 'none' })
      })
  },
})
