// 发布预打车页。地点改为本页内联下拉（原生 picker mode=selector，预设 + 末尾"其他"），
// 不再跳转 place-picker 页（减少页面跳转，RFC-007 视觉令牌）；发布是唯一 Primary Action（UI_SPEC）。
// 幂等契约（规范 §21）：Idempotency-Key 只在首次提交时生成并存入 data，失败重试复用同一 key。
import { createRide, CreateRideRequest, PlaceRef } from '../../services/ride'
import { listPlaces, Place } from '../../services/place'
import { uuid } from '../../services/request'

// CUSTOM 地点契约：text 1..100 字（快照写入 origin_text / destination_text）
const CUSTOM_PLACE_MAX = 100

Page({
  data: {
    origin: null as PlaceRef | null,
    destination: null as PlaceRef | null,

    // 内联下拉数据源：placeOptions = GET /places 预设地点名 + 末尾追加"其他"
    places: [] as Place[],
    placeOptions: [] as string[],
    placesLoading: true,
    placesError: false,

    // 选"其他"后展开的本页内联自定义输入（每个槽位独立开关）
    originCustomOpen: false,
    originCustomText: '',
    destinationCustomOpen: false,
    destinationCustomText: '',

    departDate: '',
    departTime: '18:00',
    memberCount: 1,
    capacity: 4,
    remark: '',
    submitting: false,
    minDate: '',
    maxDate: '',

    // 幂等键：首次提交生成，失败重试复用；成功后清空（下一次发布需要新 key）
    idemKey: '',
  },

  onLoad() {
    const now = new Date()
    const pad = (n: number) => `${n}`.padStart(2, '0')
    const today = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
    const max = new Date(now.getFullYear(), now.getMonth() + 1, now.getDate())
    const maxDate = `${max.getFullYear()}-${pad(max.getMonth() + 1)}-${pad(max.getDate())}`
    this.setData({ departDate: today, minDate: today, maxDate })
    this.loadPlaces()
  },

  /** 拉取管理员预设地点（V1 禁止模糊搜索，客户端全量拉取后内联下拉展示）。 */
  loadPlaces() {
    this.setData({ placesLoading: true, placesError: false })
    listPlaces()
      .then((places) => {
        // 末尾固定追加"其他"：选中后展开本页 CUSTOM 输入框
        this.setData({
          places,
          placeOptions: places.map((p) => p.name).concat('其他'),
          placesLoading: false,
        })
      })
      .catch(() => {
        // 降级：预设地点拉取失败不影响手动发布，提供重试入口
        this.setData({ placesLoading: false, placesError: true })
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
        this.setData({ idemKey: '' })
        wx.showToast({ title: '发布成功', icon: 'success' })
        setTimeout(() => wx.navigateBack(), 600)
      })
      .catch((err: Error) => {
        // 失败保留 idemKey，用户重试仍用同一 key，服务端幂等去重
        this.setData({ submitting: false })
        if (err.message === '当前委托较多，请完成或下架后再发布') {
          wx.showModal({ title: '暂不能发布', content: err.message, showCancel: false })
          return
        }
        wx.showToast({ title: err.message, icon: 'none' })
      })
  },
})
