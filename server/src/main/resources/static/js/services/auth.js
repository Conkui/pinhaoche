// 登录与会话状态（Web 版，RFC-016）。对应小程序 app.ts（globalData + ensureLogin）+ services/auth.ts 的合体。
//
// 与小程序的关键差异：Web 没有 wx.login，无法走真实 jscode2session（RFC-016 硬边界）。
// V1 Web 只支持后端 WECHAT_MOCK=true：localStorage 持久化固定 dev code，mock 网关把 code 当
// openid → 身份稳定（Redis 重启重登也不会漂移成新用户，与小程序 DEV_MOCK_LOGIN 同语义）。
//
// 本文件故意不 import request.js：登录请求用裸 fetch 直发。原因与小程序 auth.ts 绕过
// request 封装一致——登录接口自身的 401 必须直接抛出，绝不能触发"重登"逻辑，
// 否则同样的失败原因再打一次 /auth/login 会形成无限循环（request.js 依赖本模块做 401 重登）。
import { BASE_URL, STORAGE_KEYS } from '../config.js'
import { uuid } from '../utils/id.js'

/** @typedef {{id: number, nickname: string, avatar?: string}} SessionUser */

/** @type {string} */
let token = ''
/** @type {SessionUser|null} */
let user = null

function readStorage(key) {
  try {
    return window.localStorage.getItem(key)
  } catch (e) {
    // 隐私模式/配额异常下 localStorage 不可用：降级为内存态（本次会话内仍可用，刷新需重登）
    return null
  }
}

function writeStorage(key, value) {
  try {
    window.localStorage.setItem(key, value)
  } catch (e) {
    // 写失败不阻断登录流程：登录态仍在内存中，本次会话可继续用
  }
}

function removeStorage(key) {
  try {
    window.localStorage.removeItem(key)
  } catch (e) {
    // 同上：内存态已清理，storage 清理失败无实际影响
  }
}

// 冷启动恢复登录态：与小程序 app.ts onLaunch 的 storage 恢复同语义
token = readStorage(STORAGE_KEYS.token) ?? ''
const storedUser = readStorage(STORAGE_KEYS.user)
if (storedUser) {
  try {
    user = JSON.parse(storedUser)
  } catch (e) {
    user = null // 缓存损坏按未登录处理，走重登
  }
}

export function getToken() {
  return token
}

/** @returns {SessionUser|null} */
export function getUser() {
  return user
}

/** 登录成功后写入内存 + localStorage；app.js 的资料更新也走这里回写。 */
export function setSession(newToken, newUser) {
  token = newToken
  user = newUser
  writeStorage(STORAGE_KEYS.token, newToken)
  writeStorage(STORAGE_KEYS.user, JSON.stringify(newUser))
}

/** 清空会话（401 重登前调用）：storage 不清的话重试时 getToken() 仍拿到旧 token，重试必然再 401。 */
export function clearSession() {
  token = ''
  user = null
  removeStorage(STORAGE_KEYS.token)
  removeStorage(STORAGE_KEYS.user)
}

/**
 * 获取 mock 登录 code：首次生成 uuid 持久化，之后恒定复用。
 * 为什么必须持久化：Web 与小程序 dev 模式同理——每次随机新 code 会被 mock 网关当成新 openid，
 * 重登就漂移成新用户（"我的发布"看不到旧单）。
 */
function obtainDevCode() {
  const existing = readStorage(STORAGE_KEYS.devCode)
  if (existing) {
    return existing
  }
  const generated = uuid()
  writeStorage(STORAGE_KEYS.devCode, generated)
  return generated
}

/** POST /auth/login（裸 fetch，理由见文件头注释）。 */
async function login() {
  let res
  try {
    res = await fetch(`${BASE_URL}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: obtainDevCode() }),
    })
  } catch (e) {
    throw new Error('登录网络异常，请检查网络后重试')
  }
  if (!res.ok) {
    // 登录失败直接抛出：401/429 等都交由调用方呈现，绝不进入重登循环
    throw new Error(`登录失败(${res.status})，请稍后重试`)
  }
  const data = await res.json()
  setSession(data.token, data.user)
  return data.user
}

/**
 * 确保已登录（单飞）。force=true：先清空旧凭证再重新登录——供 request.js 在业务 401（token 过期）后调用；
 * 不加 force 时短路检查会直接返回现有登录态，起不到刷新作用（与小程序 ensureLogin 同语义）。
 * 单飞原因：首屏并发多个请求同时收到 401 时，共享同一次重登，避免撞上登录接口 20 次/分钟的 IP 限流（RFC-010）。
 *
 * @param {boolean} [force]
 * @returns {Promise<SessionUser>}
 */
let ensureInFlight = null
export function ensureLogin(force = false) {
  if (force) {
    clearSession()
  }
  if (token && user) {
    return Promise.resolve(user)
  }
  if (!ensureInFlight) {
    ensureInFlight = login().finally(() => {
      ensureInFlight = null
    })
  }
  return ensureInFlight
}
