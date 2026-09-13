// HTTP 请求封装：
// - 自动携带 Authorization: Bearer token（身份只来自服务端登录，见后端 AuthInterceptor）
// - POST 自动生成 Idempotency-Key（openapi 契约要求，规范 §21）
// - 统一把 ApiError {code,message} 转为 reject，调用方按 UI_SPEC 呈现失败重试
import { BASE_URL } from '../config'

export interface ApiError {
  code: string
  message: string
}

export class HttpError extends Error {
  readonly code: string
  readonly statusCode: number

  constructor(code: string, message: string, statusCode: number) {
    super(message)
    this.code = code
    this.statusCode = statusCode
  }
}

export function getToken(): string {
  return wx.getStorageSync('phc_token') as string
}

export function uuid(): string {
  // 无第三方依赖的 uuid v4（clientMsgId / Idempotency-Key 通用）
  const hex = '0123456789abcdef'
  let s = ''
  for (let i = 0; i < 36; i++) {
    if (i === 8 || i === 13 || i === 18 || i === 23) {
      s += '-'
    } else if (i === 14) {
      s += '4'
    } else if (i === 19) {
      s += hex[(Math.floor(Math.random() * 4) + 8) % 16]
    } else {
      s += hex[Math.floor(Math.random() * 16)]
    }
  }
  return s
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  data?: unknown
  /** 幂等键复用：失败重试必须复用原 key，否则服务端会当成新请求。 */
  idempotencyKey?: string
}

export function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? 'GET'
  const header: Record<string, string> = {
    Authorization: `Bearer ${getToken()}`,
  }
  if (method === 'POST') {
    header['Idempotency-Key'] = options.idempotencyKey ?? uuid()
  }
  return new Promise((resolve, reject) => {
    wx.request({
      url: `${BASE_URL}${path}`,
      method,
      data: options.data as string | object | undefined,
      header,
      success: (res) => {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          resolve(res.data as T)
          return
        }
        const body = (res.data ?? {}) as Partial<ApiError>
        reject(new HttpError(body.code ?? 'UNKNOWN', body.message ?? `请求失败(${res.statusCode})`, res.statusCode))
      },
      fail: () => reject(new HttpError('NETWORK', '网络异常，请检查网络后重试', 0)),
    })
  })
}
