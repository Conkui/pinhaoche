// Web 版应用壳：hash 路由 + 头部/底栏 + 全局登录编排 + 全局异常捕获。
// 对应小程序的 app.json（页面表/tabBar）+ app.ts（登录态/全局异常/页面不存在兜底）。
//
// ============ 页面模块契约（Ride 域与 Chat 域 Agent 共同遵守，RFC-016） ============
// 每个页面是一个 ES 模块，默认导出：
//   export default {
//     async render(view, ctx) { ... },  // view=#phc-view 容器，ctx={params, query}
//     destroy() { ... },                // 可选；清理定时器/WS/abort 标记，切页前必被调用
//   }
// - ctx.params 由路由表 keys 填充（如 /ride/:rideId → ctx.params.rideId），query 为查询串对象。
// - render 内所有异步回调必须在 destroy() 置 destroyed 标记后放弃写入 view（晚到的响应不许污染下一页）。
// - 页面标题/isTab 统一在本文件路由表维护（单一事实源），页面模块不要自带。
// - 动态插值进 innerHTML 的字符串必须过 utils/format.js 的 escapeHTML（XSS 最后防线）。
// - 反馈组件用 ui.js 的 toast/confirmDialog/showLoading/emptyStateHTML/errorStateHTML。
// ============================================================
import { ensureLogin, getUser } from './services/auth.js'
import { errorStateHTML } from './ui.js'

/**
 * 路由表。title/isTab 集中在此（单一事实源）；loader 用动态 import 保持首屏只加载当前页。
 * @type {Array<{path: RegExp, keys: string[], title: string, isTab: boolean, loader: () => Promise<*>}>}
 */
const ROUTES = [
  { path: /^\/booked$/, keys: [], title: '实时拼', isTab: true, loader: () => import('./pages/booked.js') },
  { path: /^\/prebook$/, keys: [], title: '找拼友', isTab: true, loader: () => import('./pages/prebook.js') },
  { path: /^\/messages$/, keys: [], title: '消息', isTab: true, loader: () => import('./pages/messages.js') },
  { path: /^\/profile$/, keys: [], title: '我的', isTab: true, loader: () => import('./pages/profile.js') },
  { path: /^\/publish$/, keys: [], title: '发布预打车', isTab: false, loader: () => import('./pages/publish.js') },
  { path: /^\/import$/, keys: [], title: '导入已打上', isTab: false, loader: () => import('./pages/import.js') },
  { path: /^\/ride\/(\d+)$/, keys: ['rideId'], title: '拼车详情', isTab: false, loader: () => import('./pages/ride-detail.js') },
  { path: /^\/my-rides$/, keys: [], title: '我的发布', isTab: false, loader: () => import('./pages/my-rides.js') },
  { path: /^\/chat\/(\d+)$/, keys: ['sessionId'], title: '聊天', isTab: false, loader: () => import('./pages/chat.js') },
  { path: /^\/feedback$/, keys: [], title: '意见反馈', isTab: false, loader: () => import('./pages/feedback.js') },
]

// 底栏 4 个 tab：文案与小程序 app.json tabBar 一致（纯文字无图标，形态对齐）。
const TABS = [
  { path: '/booked', text: '实时拼' },
  { path: '/prebook', text: '找拼友' },
  { path: '/messages', text: '消息' },
  { path: '/profile', text: '我的' },
]

const view = /** @type {HTMLElement} */ (document.getElementById('phc-view'))
const headerEl = /** @type {HTMLElement} */ (document.getElementById('phc-header'))
const headerTitleEl = /** @type {HTMLElement} */ (document.getElementById('phc-header-title'))
const headerBackEl = /** @type {HTMLButtonElement} */ (document.getElementById('phc-header-back'))
const tabbarEl = /** @type {HTMLElement} */ (document.getElementById('phc-tabbar'))

/** 当前页面实例的 destroy；切页/重渲染前调用。@type {(()=>void)|null} */
let currentPageDestroy = null
// 路由竞态序号：路由切换是异步的（动态 import + render），旧切换的晚到结果不得覆盖新页面
let routeSeq = 0

/** 解析 location.hash → {path, query}；'#' 或空 → '/booked'。 */
function parseHash() {
  const raw = window.location.hash.replace(/^#/, '') || '/booked'
  const qIndex = raw.indexOf('?')
  const path = qIndex === -1 ? raw : raw.slice(0, qIndex)
  /** @type {Record<string, string>} */
  const query = {}
  if (qIndex !== -1) {
    for (const [k, v] of new URLSearchParams(raw.slice(qIndex + 1))) {
      query[k] = v
    }
  }
  return { path, query }
}

/** 跳转（新增历史记录）。@param {string} path 形如 '/ride/3' 或 '/chat/5?x=1' */
export function navigate(path) {
  window.location.hash = path
}

/** 替换当前历史（不产生返回记录）。 */
export function replace(path) {
  const url = `${window.location.pathname}${window.location.search}#${path}`
  window.history.replaceState(null, '', url)
  // replaceState 不触发 hashchange，手动渲染
  renderRoute()
}

/** 返回上一页；无历史（直接深链进入）时兜底回首页 tab（对齐小程序 onPageNotFound 兜底语义）。 */
export function back() {
  if (window.history.length > 1) {
    window.history.back()
  } else {
    replace('/booked')
  }
}

// ============ 登录失败全屏态（UI_SPEC：失败给重试入口，不允许白屏死等） ============
function renderLoginError(err) {
  view.innerHTML = `
    <div class="phc-login-error">
      <div class="phc-empty__text">登录失败</div>
      <div class="phc-empty__hint"></div>
      <button type="button" class="phc-btn phc-login-error__retry">重新登录</button>
    </div>`
  /** @type {HTMLElement} */ (view.querySelector('.phc-empty__hint')).textContent = err?.message ?? '请稍后重试'
  /** @type {HTMLButtonElement} */ (view.querySelector('.phc-login-error__retry')).addEventListener('click', async (e) => {
    const btn = /** @type {HTMLButtonElement} */ (e.currentTarget)
    btn.disabled = true
    try {
      await ensureLogin(true)
      renderRoute()
    } catch (retryErr) {
      btn.disabled = false
      renderLoginError(/** @type {Error} */ (retryErr))
    }
  })
}

/**
 * 路由渲染主流程：登录 → 销毁旧页 → 动态加载 → 渲染。
 * hashchange 与 replace 都会走到这里；用 routeSeq 防竞态。
 */
async function renderRoute() {
  const seq = ++routeSeq
  const { path, query } = parseHash()
  const route = ROUTES.find((r) => r.path.test(path))

  // 先销毁旧页面（聊天 WS/定时器必须在加载新模块前关闭，避免双连接双推送）
  if (currentPageDestroy) {
    const destroy = currentPageDestroy
    currentPageDestroy = null
    try {
      destroy()
    } catch (e) {
      console.warn('[app] 页面清理失败（忽略，继续切页）', e)
    }
  }
  view.innerHTML = ''
  window.scrollTo(0, 0)

  // 未匹配路由：兜底回首页 tab（对齐小程序 onPageNotFound → switchTab booked）
  if (!route) {
    replace('/booked')
    return
  }

  // 头部与底栏形态：tab 页无返回键；非 tab 页隐藏底栏（对齐小程序"仅 tab 页有 tabBar"）
  headerEl.hidden = false
  headerTitleEl.textContent = route.title
  headerBackEl.hidden = route.isTab
  tabbarEl.hidden = !route.isTab
  for (const tab of tabbarEl.children) {
    const tabPath = /** @type {HTMLElement} */ (tab).dataset.path
    /** @type {HTMLElement} */ (tab).classList.toggle('is-active', tabPath === path)
  }

  // 登录门禁：所有页面都需要登录态（静默登录失败给全屏重试，不白屏）
  try {
    await ensureLogin()
  } catch (err) {
    console.error('[app] 登录失败', err)
    if (seq === routeSeq) {
      renderLoginError(/** @type {Error} */ (err))
    }
    return
  }
  if (seq !== routeSeq) {
    return // 登录等待期间用户又切了路由
  }

  try {
    const module = await route.loader()
    if (seq !== routeSeq) {
      return // import 期间又切了路由：丢弃旧结果
    }
    const page = module.default
    if (!page || typeof page.render !== 'function') {
      throw new Error(`页面模块缺少 default export render: ${path}`)
    }
    currentPageDestroy = typeof page.destroy === 'function' ? () => page.destroy() : null
    await page.render(view, { params: buildParams(route, path), query })
  } catch (err) {
    console.error('[app] 页面渲染失败', path, err)
    if (seq !== routeSeq) {
      return
    }
    currentPageDestroy = null
    // 渲染失败按 UI_SPEC 给重试入口，而不是把用户留在白屏
    view.innerHTML = ''
    view.appendChild(errorStateHTML(/** @type {Error} */ (err)?.message ?? '请稍后重试', () => renderRoute()))
  }
}

/** 按路由表 keys 把正则捕获组变成 ctx.params。 */
function buildParams(route, path) {
  const match = route.path.exec(path)
  /** @type {Record<string, string>} */
  const params = {}
  route.keys.forEach((key, i) => {
    params[key] = /** @type {RegExpExecArray} */ (match)[i + 1]
  })
  return params
}

// ============ 静态壳构建 ============
function buildHeader() {
  headerBackEl.addEventListener('click', back)
}

function buildTabbar() {
  tabbarEl.innerHTML = TABS.map(
    (t) => `<button type="button" class="phc-tabbar__item" data-path="${t.path}">${t.text}</button>`,
  ).join('')
  tabbarEl.addEventListener('click', (e) => {
    const item = /** @type {HTMLElement|null} */ (/** @type {HTMLElement} */ (e.target).closest('.phc-tabbar__item'))
    if (item && item.dataset.path) {
      navigate(item.dataset.path)
    }
  })
}

// ============ 全局异常捕获（对齐小程序 app.ts onError / onUnhandledRejection） ============
// 仅记录不提示用户：未捕获异常多数不影响主流程，逐个弹 toast 反而骚扰；
// 页面级可恢复错误由页面自己 toast（UI_SPEC）。
window.addEventListener('error', (e) => console.error('[app.onError]', e.error ?? e.message))
window.addEventListener('unhandledrejection', (e) => console.error('[app.unhandledRejection]', e.reason))

// 调试辅助：控制台可 getUser() 看当前登录身份（不挂任何可变全局，避免与模块状态漂移）
window.PHC_DEBUG = Object.freeze({ getUser })

buildHeader()
buildTabbar()
window.addEventListener('hashchange', renderRoute)
renderRoute()
