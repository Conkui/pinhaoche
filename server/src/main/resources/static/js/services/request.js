// HTTP 请求封装（从小程序 services/request.ts 移植到 fetch，语义保持一致）：
// - 自动携带 Authorization: Bearer token（身份只来自服务端登录，见后端 AuthInterceptor）
// - POST 自动生成 Idempotency-Key（openapi 契约要求，规范 §21）；401 重登后的重试复用同一 key
// - 统一把 ApiError {code,message} 转为 reject 的 HttpError，调用方按 UI_SPEC 呈现失败重试
// - 业务 401（token 过期）时单飞自动重登并重试原请求一次（RFC-010）；429 RATE_LIMITED 规范默认文案
import { BASE_URL } from '../config.js'
import { ensureLogin, getToken } from './auth.js'

export class HttpError extends Error {
  /**
   * @param {string} code
   * @param {string} message
   * @param {number} statusCode
   */
  constructor(code, message, statusCode) {
    super(message)
    this.name = 'HttpError'
    /** @type {string} */
    this.code = code
    /** @type {number} */
    this.statusCode = statusCode
  }
}

/**
 * @typedef {Object} RequestOptions
 * @property {'GET'|'POST'|'PUT'|'DELETE'} [method]
 * @property {unknown} [data]
 * @property {string} [idempotencyKey]  幂等键复用：失败重试必须复用原 key，否则服务端会当成新请求。
 */

// 登录接口路径标记：登录接口自身的 401 必须直接抛出、绝不能再触发"重登"
// （会用同样的失败原因再打一次 /auth/login 形成无限循环；本封装已不含登录调用，
// 此守卫为直接用 request() 打登录接口的误用兜底）。
const LOGIN_PATH_MARKER = '/auth/login'

// 429 限流的统一文案（RFC-010）：后端 message 可能是英文/技术性描述，封装层规范成用户可读文案。
const RATE_LIMITED_MESSAGE = '操作太频繁，请稍后再试'

// 正在进行中的重登 Promise（单飞状态槽）。
// 为什么单飞：首屏往往并发多个请求，token 过期时会同时收到 N 个 401；
// 若各自触发重登就是 N 次 /auth/login——既浪费又会撞登录接口 20 次/分钟的 IP 限流（RFC-010）。
/** @type {Promise<void>|null} */
let reloginInFlight = null

async function doRelogin() {
  // clearSession 在 ensureLogin(true) 内部完成：不清旧 token 的话重试必然再 401
  await ensureLogin(true)
}

/** 单飞入口：同一时刻只允许一次重登在途；结算后清空槽位，下次真正的过期可再次触发。 */
function reloginOnce() {
  if (!reloginInFlight) {
    reloginInFlight = doRelogin().finally(() => {
      reloginInFlight = null
    })
  }
  return reloginInFlight
}

/** 是否为"token 过期"型业务 401（只有 HttpError 且 statusCode===401 才触发自动重登）。 */
function isAuthExpiredError(err) {
  return err instanceof HttpError && err.statusCode === 401
}

/**
 * 单次真实请求（不含 401 重登编排）。idempotencyKey 由 request() 入口确定后传入，保证重试复用同一 key。
 *
 * @template T
 * @param {string} path
 * @param {RequestOptions} options
 * @param {string|undefined} idempotencyKey
 * @returns {Promise<T>}
 */
async function doRequest(path, options, idempotencyKey) {
  const method = options.method ?? 'GET'
  // 每次调用都重新读会话状态：重登完成后重试天然拿到新 token
  const headers = { Authorization: `Bearer ${getToken()}` }
  /** @type {string|undefined} */
  let body
  if (options.data !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(options.data)
  }
  if (method === 'POST') {
    headers['Idempotency-Key'] = idempotencyKey ?? ''
  }
  let res
  try {
    res = await fetch(`${BASE_URL}${path}`, { method, headers, body })
  } catch (e) {
    // fetch 只在网络层失败时 reject（断网/DNS/跨域），HTTP 错误状态不 reject
    throw new HttpError('NETWORK', '网络异常，请检查网络后重试', 0)
  }
  if (res.ok) {
    // void 端点（如 markRead/feedback）返回空 body：读文本判空，不能盲目 res.json()（空串直接抛 SyntaxError）
    const text = await res.text()
    if (!text) {
      return /** @type {T} */ (undefined)
    }
    try {
      return JSON.parse(text)
    } catch (e) {
      throw new HttpError('UNKNOWN', '响应解析失败，请重试', res.status)
    }
  }
  // 非 2xx：按契约解析 ApiError {code,message}；body 非 JSON/为空时退化为 UNKNOWN
  let code = 'UNKNOWN'
  let message = `请求失败(${res.status})`
  try {
    const errBody = await res.json()
    if (errBody && typeof errBody.code === 'string') {
      code = errBody.code
      message = code === 'RATE_LIMITED' ? RATE_LIMITED_MESSAGE : errBody.message ?? message
    }
  } catch (e) {
    // 保留默认 UNKNOWN 文案
  }
  throw new HttpError(code, message, res.status)
}

/**
 * 统一请求入口（对页面签名保持不变，重登/重试对调用方透明）。
 * 401 编排：重登 → 用新 token 重试原请求一次；重登失败 → reject 原 401 错误。
 *
 * @template T
 * @param {string} path
 * @param {RequestOptions} [options]
 * @returns {Promise<T>}
 */
export function request(path, options = {}) {
  // 幂等键在入口确定一次：401 重登后的重试必须复用同一个 key——服务端幂等表按 key 去重，
  // 换新 key 等于放行重复创建（发布单/加入拼车等会重复落库）；调用方显式传入的 key 优先。
  const idempotencyKey = options.idempotencyKey ?? (options.method === 'POST' ? uuid() : undefined)
  // 记录发出时的 token：若收到 401 时会话已被并发请求的重登刷新过，
  // 说明本次 401 只是"带着旧 token 的在途请求"，直接用新 token 重试即可，避免多余的二次重登。
  const tokenBefore = getToken()

  // 为什么只重试一次：重登拿到的新 token 仍 401，说明是服务端会话/账号层异常而非简单过期，
  // 继续自动重试只会造成 /auth/login 风暴（登录接口有 20 次/分钟限流，RFC-010），
  // 此时应交还页面按 UI_SPEC 提示用户手动重试。
  return doRequest(path, options, idempotencyKey).catch((err) => {
    if (!isAuthExpiredError(err) || path.includes(LOGIN_PATH_MARKER)) {
      throw err
    }
    if (getToken() !== tokenBefore) {
      // token 已被并发的重登刷新 → 跳过重登直接重试
      return doRequest(path, options, idempotencyKey)
    }
    return reloginOnce().then(
      () => doRequest(path, options, idempotencyKey),
      (reloginErr) => {
        // 重登失败：按约定 reject 原 401 错误，让页面按 UI_SPEC 呈现失败重试，
        // 而不是把重登内部错误（登录接口限流等）暴露给页面
        console.warn('[request] 自动重登失败', reloginErr)
        throw err
      },
    )
  })
}
