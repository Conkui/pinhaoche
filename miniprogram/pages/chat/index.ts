// 聊天页（Agent G 边界）：消息列表 / WebSocket / ACK 三态 / 断线重连补偿 / 快捷文本。
// 消息状态：sending（未 ACK）/ acked / failed（复用原 clientMsgId 重试，规范 §12 幂等）。
import * as chatApi from '../../services/chat'
import { Message } from '../../services/chat'
import { ChatSocket } from '../../services/ws'
import { uuid } from '../../services/request'
import { formatClock } from '../../utils/format'

interface Bubble {
  key: string          // 渲染 key：服务端消息用 msg-<id>，本端未确认消息用 c-<clientMsgId>
  messageId: number    // 0 = 尚未确认
  clientMsgId: string
  mine: boolean
  content: string
  time: string
  state: 'sending' | 'acked' | 'failed'
}

const QUICK_TEXTS = [
  '还有位置吗？',
  '我也去这个地方，可以等我一下吗？',
  '我马上到出发点。',
]

Page({
  data: {
    sessionId: 0,
    peerUserId: 0,
    myUserId: 0,
    bubbles: [] as Bubble[],
    input: '',
    quickTexts: QUICK_TEXTS,
    connected: false,
    historyLoading: true,
    historyFailed: false,
    scrollTop: 99999,
  },

  socket: null as ChatSocket | null,
  /** 待确认消息注册表：clientMsgId → 下标，ACK/ERROR 时定位更新三态。 */
  pendingIndex: {} as Record<string, number>,
  /** 本地最大 messageId，断线重连后据此补偿。 */
  lastMessageId: 0,

  onLoad(query: Record<string, string>) {
    const app = getApp()
    const sessionId = Number(query.sessionId)
    const peerUserId = Number(query.peerUserId)
    this.setData({
      sessionId,
      peerUserId,
      myUserId: app.globalData.user?.id ?? 0,
    })

    // 1) 先拉历史（含未读补偿），2) 再建 WebSocket（协议：ENTER_SESSION 服务端清未读）
    chatApi.fetchMessages(sessionId, 0)
      .then((list: Message[]) => {
        this.applyHistory(list)
        this.setData({ historyLoading: false })
        this.connectSocket()
      })
      .catch(() => {
        this.setData({ historyLoading: false, historyFailed: true })
        this.connectSocket()
      })
  },

  onUnload() {
    this.socket?.leave()
  },

  retryHistory() {
    this.setData({ historyLoading: true, historyFailed: false })
    chatApi.fetchMessages(this.data.sessionId, 0)
      .then((list: Message[]) => {
        this.applyHistory(list)
        this.setData({ historyLoading: false, historyFailed: false })
      })
      .catch(() => this.setData({ historyLoading: false, historyFailed: true }))
  },

  applyHistory(list: Message[]) {
    const myId = this.data.myUserId
    const bubbles = list.map((m) => ({
      key: `msg-${m.id}`,
      messageId: m.id,
      clientMsgId: m.clientMsgId,
      mine: m.senderId === myId,
      content: m.content,
      time: formatClock(m.createdAt),
      state: 'acked' as const,
    }))
    this.lastMessageId = list.length > 0 ? list[list.length - 1].id : 0
    this.setData({ bubbles, scrollTop: bubbles.length * 1000 })
  },

  connectSocket() {
    const socket = new ChatSocket()
    this.socket = socket
    socket.onStateChange = (connected: boolean) => {
      this.setData({ connected })
      if (connected) {
        // 断线重连补偿：增量拉取 lastMessageId 之后的消息（docs/WEBSOCKET_PROTOCOL.md）
        chatApi.fetchMessages(this.data.sessionId, this.lastMessageId)
          .then((list: Message[]) => this.appendIncoming(list))
          .catch(() => undefined)
      }
    }
    socket.onMessage = (frame) => this.onFrame(frame)
    socket.connect(this.data.sessionId)
  },

  onFrame(frame: { type: string; [k: string]: unknown }) {
    if (frame.type === 'MESSAGE') {
      const m = frame.message as Message
      this.appendIncoming([m])
    } else if (frame.type === 'ACK') {
      const clientMsgId = frame.clientMsgId as string
      const messageId = frame.messageId as number
      const idx = this.pendingIndex[clientMsgId]
      if (idx != null) {
        this.updateBubble(idx, { state: 'acked', messageId })
        delete this.pendingIndex[clientMsgId]
        if (messageId > this.lastMessageId) {
          this.lastMessageId = messageId
        }
      }
    } else if (frame.type === 'ERROR') {
      const clientMsgId = frame.clientMsgId as string
      const idx = this.pendingIndex[clientMsgId]
      if (idx != null) {
        this.updateBubble(idx, { state: 'failed' })
        delete this.pendingIndex[clientMsgId]
      }
    }
  },

  appendIncoming(list: Message[]) {
    if (list.length === 0) {
      return
    }
    const myId = this.data.myUserId
    const fresh: Bubble[] = list.map((m) => ({
      key: `msg-${m.id}`,
      messageId: m.id,
      clientMsgId: m.clientMsgId,
      mine: m.senderId === myId,
      content: m.content,
      time: formatClock(m.createdAt),
      state: 'acked' as const,
    }))
    const last = list[list.length - 1]
    if (last.id > this.lastMessageId) {
      this.lastMessageId = last.id
    }
    this.setData({
      bubbles: this.data.bubbles.concat(fresh),
      scrollTop: this.data.bubbles.length * 1000 + fresh.length * 1000,
    })
  },

  updateBubble(idx: number, patch: Partial<Bubble>) {
    const key = `bubbles[${idx}]`
    const data: Record<string, unknown> = {}
    Object.entries(patch).forEach(([k, v]) => {
      data[`${key}.${k}`] = v
    })
    this.setData(data)
  },

  onInput(e: WechatMiniprogram.Input) {
    this.setData({ input: e.detail.value })
  },

  /** 发送：先落 UI 三态 sending，收到 ACK → acked，ERROR → failed（可点重试，复用 clientMsgId）。 */
  send(content: string) {
    const text = content.trim()
    if (!text || !this.socket) {
      return
    }
    const clientMsgId = uuid()
    const idx = this.data.bubbles.length
    this.pendingIndex[clientMsgId] = idx
    this.setData({
      bubbles: this.data.bubbles.concat([{
        key: `c-${clientMsgId}`,
        messageId: 0,
        clientMsgId,
        mine: true,
        content: text,
        time: formatClock(new Date().toISOString()),
        state: 'sending',
      }]),
      input: '',
      scrollTop: (idx + 1) * 1000,
    })
    const ok = this.socket.sendMessage(clientMsgId, text)
    if (!ok) {
      // 连接断开：立即标记失败，等重连后用户手动重试（复用同一 clientMsgId）
      wx.nextTick(() => this.markFailed(clientMsgId))
    }
  },

  onSend() {
    this.send(this.data.input)
  },

  onQuickText(e: WechatMiniprogram.TouchEvent) {
    const text = String(e.currentTarget.dataset.text)
    this.send(text)
  },

  markFailed(clientMsgId: string) {
    const idx = this.pendingIndex[clientMsgId]
    if (idx != null) {
      this.updateBubble(idx, { state: 'failed' })
      delete this.pendingIndex[clientMsgId]
    }
  },

  /** 失败重试：复用原 clientMsgId（服务端 (sender_id, client_msg_id) 唯一键保证不重复落库）。 */
  retrySend(e: WechatMiniprogram.TouchEvent) {
    const clientMsgId = String(e.currentTarget.dataset.cmid)
    const idx = this.data.bubbles.findIndex((b) => b.clientMsgId === clientMsgId && b.state === 'failed')
    if (idx < 0 || !this.socket) {
      return
    }
    this.pendingIndex[clientMsgId] = idx
    this.updateBubble(idx, { state: 'sending' })
    const ok = this.socket.sendMessage(clientMsgId, this.data.bubbles[idx].content)
    if (!ok) {
      this.markFailed(clientMsgId)
    }
  },
})
