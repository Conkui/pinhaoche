// 地点选择页（规范 §7）：先列启用的预设地点（GET /places），最后提供"自定义填写"。
// 自定义地点仅属于当前拼车记录（CUSTOM），不进入公共地点库（规范 §2.2）。
import { listPlaces, Place } from '../../services/place'
import { PlaceRef } from '../../services/ride'

Page({
  data: {
    slot: 'origin',
    current: '',
    loading: true,
    loadFailed: false,
    places: [] as Place[],
    customText: '',
    showCustom: false,
  },

  onLoad(query: Record<string, string>) {
    this.setData({
      slot: query.slot ?? 'origin',
      current: query.current ? decodeURIComponent(query.current) : '',
    })
    this.reload()
  },

  reload() {
    this.setData({ loading: true, loadFailed: false })
    listPlaces()
      .then((places: Place[]) => this.setData({ places, loading: false }))
      .catch(() => this.setData({ loading: false, loadFailed: true }))
  },

  retry() {
    this.reload()
  },

  /** 选中预设地点：回传 PRESET 引用（含 placeId + 服务端快照名）。 */
  pickPreset(e: WechatMiniprogram.TouchEvent) {
    const place = this.data.places.find((p) => p.id === e.currentTarget.dataset.id)
    if (!place) {
      return
    }
    this.emit({ type: 'PRESET', placeId: place.id, text: place.name })
  },

  toggleCustom() {
    this.setData({ showCustom: !this.data.showCustom })
  },

  onCustomInput(e: WechatMiniprogram.Input) {
    this.setData({ customText: e.detail.value })
  },

  /** 自定义地点：仅 CUSTOM 文本，placeId 为空。 */
  confirmCustom() {
    const text = this.data.customText.trim()
    if (!text) {
      wx.showToast({ title: '请填写地点名称', icon: 'none' })
      return
    }
    if (text.length > 100) {
      wx.showToast({ title: '地点名称过长', icon: 'none' })
      return
    }
    this.emit({ type: 'CUSTOM', placeId: null, text })
  },

  emit(place: PlaceRef) {
    const channel = this.getOpenerEventChannel()
    channel.emit('placeSelected', { slot: this.data.slot, place })
    wx.navigateBack()
  },
})
