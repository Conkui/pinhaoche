// WebSocket 封装（docs/WEBSOCKET_PROTOCOL.md 自定义协议 + UI_SPEC 三态发送）。
// - 发送带 clientMsgId；未 ACK 前状态=发送中；ACK 后=已确认；ERROR 后=失败可重试（复用原 clientMsgId，规范 §12 幂等）
// - 断线自动重连；重连成功后由调用方用 afterId 经 HTTP 补偿
import { WS_URL } from '../config'
import { getToken } from './request'

export type SendState = 'sending' | 'acked' | 'failed'

export interface PendingMessage {
  clientMsgId: string
  content: string
  state: SendState
}

interface Frame {
  type: string
  [key: string]: unknown
}

export class ChatSocket {
  private task: WechatMiniprogram.SocketTask | null = null
  private sessionId = 0
  private reconnecting = false
  private closedByUser = false

  onMessage: ((frame: Frame) => void) | null = null
  onStateChange: ((connected: boolean) => void) | null = null

  connect(sessionId: number) {
    this.sessionId = sessionId
    this.closedByUser = false
    this.open()
  }

  private open() {
    this.task = wx.connectSocket({
      url: `${WS_URL}?token=${getToken()}`,
      fail: () => this.scheduleReconnect(),
    })
    this.task.onOpen(() => {
      this.onStateChange?.(true)
      // 进入会话：服务端据此判定"在聊天页"（不发订阅消息）并清未读
      this.sendRaw({ type: 'ENTER_SESSION', sessionId: this.sessionId })
    })
    this.task.onMessage((res) => {
      try {
        this.onMessage?.(JSON.parse(res.data as string) as Frame)
      } catch (e) {
        // 非 JSON 帧直接丢弃（协议约定未知类型服务端也回 ERROR，不会静默）
      }
    })
    this.task.onClose(() => {
      this.onStateChange?.(false)
      this.scheduleReconnect()
    })
    this.task.onError(() => {
      this.onStateChange?.(false)
    })
  }

  /** 心跳：微信小程序 WS 无自动 ping，60s 手动保活。 */
  ping() {
    this.sendRaw({ type: 'PING' })
  }

  /** 发送文本。clientMsgId 由调用方生成并保存，重试复用同一 id（消息幂等，规范 §12）。 */
  sendMessage(clientMsgId: string, content: string): boolean {
    return this.sendRaw({
      type: 'SEND_MESSAGE',
      clientMsgId,
      sessionId: this.sessionId,
      content,
      timestamp: 0,
    })
  }

  private sendRaw(frame: Frame): boolean {
    if (!this.task) {
      return false
    }
    let ok = true
    this.task.send({
      data: JSON.stringify(frame),
      fail: () => {
        ok = false
      },
    })
    return ok
  }

  leave() {
    if (this.sessionId > 0) {
      this.sendRaw({ type: 'EXIT_SESSION', sessionId: this.sessionId })
    }
    this.closedByUser = true
    this.task?.close({})
    this.task = null
  }

  private scheduleReconnect() {
    if (this.closedByUser || this.reconnecting) {
      return
    }
    this.reconnecting = true
    setTimeout(() => {
      this.reconnecting = false
      if (!this.closedByUser) {
        this.open()
      }
    }, 3000)
  }
}
