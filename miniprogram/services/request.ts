// HTTP 请求封装：
// - 自动携带 Authorization: Bearer token（身份只来自服务端登录，见后端 AuthInterceptor）
// - POST 自动生成 Idempotency-Key（openapi 契约要求，规范 §21）；401 重登后的重试复用同一 key
// - 统一把 ApiError {code,message} 转为 reject，调用方按 UI_SPEC 呈现失败重试
// - 业务 401（token 过期）时单飞自动重登并重试原请求一次（RFC-010）；429 RATE_LIMITED 规范默认文案
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

/**
 * app 实例上本模块依赖的最小结构（结构化类型，避免 request → app 的强耦合；
 * 用 type alias 而非 interface 才能满足 getApp<T extends IAnyObject> 的约束，且全程无 any）。
 */
type LoginCapableApp = {
  ensureLogin: (force?: boolean) => Promise<unknown>
}

/**
 * 登录接口路径标记。登录接口自身的 401 必须直接抛出、绝不能再触发"重登"：
 * 那样会用同样的失败原因再打一次 /auth/login，形成无限循环。
 * 当前 auth.ts 走 wx.request 直连不走本封装，此守卫是为后续重构兜底。
 */
const LOGIN_PATH_MARKER = '/auth/login'

/**
 * 429 限流的统一文案（RFC-010）：后端 message 可能是英文/技术性描述，
 * 这里在封装层规范成用户可读文案，页面只需展示 HttpError.message 即满足 UI_SPEC；
 * 仍然 reject HttpError（code='RATE_LIMITED'），页面可据此做差异化处理。
 */
const RATE_LIMITED_MESSAGE = '操作太频繁，请稍后再试'

/**
 * 正在进行中的重登 Promise（单飞状态槽）。
 * 为什么单飞：首屏往往并发多个请求，token 过期时会同时收到 N 个 401；
 * 若每个 401 各自触发重登，就是 N 次 wx.login + N 次 /auth/login——既浪费，
 * 又会撞上登录接口 20 次/分钟的 IP 限流（RFC-010）。单飞让并发 401 共享同一次重登，
 * 各自在新 token 就绪后重试。
 */
let reloginInFlight: Promise<void> | null = null

/**
 * 实际重登：清掉本地过期凭证 → 调 app.ensureLogin(force)。
 * storage 不清，重试时 getToken() 仍拿到旧 token，重试必然再 401；
 * globalData 由 app.ts 的 force 分支兜底清理。
 */
function doRelogin(): Promise<void> {
  try {
    wx.removeStorageSync('phc_token')
    wx.removeStorageSync('phc_user')
    return getApp<LoginCapableApp>()
      .ensureLogin(true)
      .then(() => undefined)
  } catch (e) {
    // 兜底：getApp 未就绪/storage 异常等同步错误也转成 reject，
    // 保证上层走"重登失败 → reject 原 401 错误"的降级路径而不是抛 TypeError。
    return Promise.reject(e instanceof Error ? e : new Error(String(e)))
  }
}

/** 单飞入口：同一时刻只允许一次重登在途；结算后清空槽位，下次真正的过期可再次触发。 */
function reloginOnce(): Promise<void> {
  if (reloginInFlight) {
    return reloginInFlight
  }
  reloginInFlight = doRelogin().finally(() => {
    reloginInFlight = null
  })
  return reloginInFlight
}

/** 是否为"token 过期"型业务 401（只有 HttpError 且 statusCode===401 才触发自动重登）。 */
function isAuthExpiredError(err: unknown): boolean {
  return err instanceof HttpError && err.statusCode === 401
}

/**
 * 单次真实请求（不包含 401 重登编排）。
 * idempotencyKey 由 request() 入口确定后传入，保证重试复用同一 key。
 */
function doRequest<T>(path: string, options: RequestOptions, idempotencyKey?: string): Promise<T> {
  const method = options.method ?? 'GET'
  // 每次调用都重新读 storage：重登完成后重试天然拿到新 token
  const header: Record<string, string> = {
    Authorization: `Bearer ${getToken()}`,
  }
  if (method === 'POST') {
    header['Idempotency-Key'] = idempotencyKey ?? uuid()
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
        const code = body.code ?? 'UNKNOWN'
        // 429 限流统一文案：见 RATE_LIMITED_MESSAGE 注释
        const message = code === 'RATE_LIMITED' ? RATE_LIMITED_MESSAGE : body.message ?? `请求失败(${res.statusCode})`
        reject(new HttpError(code, message, res.statusCode))
      },
      fail: () => reject(new HttpError('NETWORK', '网络异常，请检查网络后重试', 0)),
    })
  })
}

/**
 * 统一请求入口（对 service 层签名保持不变，重登/重试对调用方透明）。
 * 401 编排：重登 → 用新 token 重试原请求一次；重登失败 → reject 原 401 错误。
 */
export function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  // 幂等键在入口确定一次：401 重登后的重试必须复用同一个 key——服务端幂等表按 key 去重，
  // 换新 key 等于放行重复创建（发布单/加入拼车等会重复落库）；调用方显式传入的 key 优先。
  const idempotencyKey = options.idempotencyKey ?? (options.method === 'POST' ? uuid() : undefined)
  // 记录发出时的 token：若收到 401 时 storage 已被并发请求的重登刷新过，
  // 说明本次 401 只是"带着旧 token 的在途请求"，直接用新 token 重试即可，避免多余的二次重登。
  const tokenBefore = getToken()

  // 为什么只重试一次：重登拿到的新 token 仍 401，说明是服务端会话/账号层异常
  // （而非简单的 token 过期），继续自动重试只会造成 wx.login 与 /auth/login 风暴
  // （登录接口有 20 次/分钟限流，RFC-010），此时应交还页面按 UI_SPEC 提示用户手动重试。
  return doRequest<T>(path, options, idempotencyKey).catch((err: unknown) => {
    // 登录接口自身 401 直接抛出，防止"重登失败 → 再重登"的无限循环
    if (!isAuthExpiredError(err) || path.includes(LOGIN_PATH_MARKER)) {
      throw err
    }
    if (getToken() !== tokenBefore) {
      // token 已被并发的重登刷新 → 跳过重登直接重试
      return doRequest<T>(path, options, idempotencyKey)
    }
    return reloginOnce().then(
      () => doRequest<T>(path, options, idempotencyKey),
      (reloginErr: unknown) => {
        // 重登失败：按约定 reject 原 401 错误，让页面按 UI_SPEC 呈现失败重试，
        // 而不是把重登内部错误（wx.login 失败/登录接口限流等）暴露给页面；
        // onRejected 只挂在重登 Promise 上，重试自身的失败仍原样向页面传递。
        console.warn('[request] 自动重登失败', reloginErr)
        throw err
      },
    )
  })
}
