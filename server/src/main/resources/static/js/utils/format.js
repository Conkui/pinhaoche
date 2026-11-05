// 展示格式化纯函数（从小程序 utils/format.ts 移植，Web 版新增 escapeHTML）。
// 领域计算无副作用——同一入参恒定同输出，页面可安全地在每次渲染时反复调用（docs/ARCHITECTURE.md 函数式约定）。

/** UTC 时间字符串 → 本地展示（后端一律 UTC 存储，客户端转换时区）。 */
export function formatTime(iso) {
  const d = new Date(iso)
  const pad = (n) => `${n}`.padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export function formatClock(iso) {
  const d = new Date(iso)
  const pad = (n) => `${n}`.padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 剩余座位（架构文档示例纯函数）：负数钳制为 0。 */
export const remainingSeats = (memberCount, capacity) => Math.max(0, capacity - memberCount)

/** 剩余距离展示：OCR/导入数据必须带更新时间，不能冒充实时数据（规范 §19）。 */
export function formatDistance(km, updatedAt) {
  if (km == null) {
    return ''
  }
  const base = `距目的地约 ${km} km`
  return updatedAt ? `${base} · ${formatTime(updatedAt)}更新` : base
}

export function waitingText(minutes) {
  if (minutes == null) {
    return '等待时间不确定'
  }
  return `愿意等待 ${minutes} 分钟`
}

/**
 * 相对路径 → 可加载的绝对 URL（纯函数）。
 * 后端 app_user.avatar 存相对路径（RFC-011：如 /files/xxx.jpg），Web <img src> 必须拼上 origin。
 * 已是绝对地址时原样返回：http(s) 是服务端/cdn 地址，data: 是内联图片（本地选择后上传前的预览），
 * blob: 是浏览器本地预览地址；这几种都不该被拼接。重复调用结果一致（幂等）。
 */
export function absoluteUrl(path, origin) {
  if (!path) {
    return ''
  }
  if (/^(https?:\/\/|data:|blob:)/.test(path)) {
    return path
  }
  // origin 尾部 '/' 统一剥掉，避免出现 `http://host//files/x.jpg`
  const base = origin.endsWith('/') ? origin.slice(0, -1) : origin
  return `${base}/${path.replace(/^\/+/, '')}`
}

/** 状态 → 列表页可读标签。 */
export const STATUS_LABELS = Object.freeze({
  DRAFT: '草稿',
  PUBLISHED: '待同行',
  MATCHING: '匹配中',
  BOOKED: '已打上',
  IN_PROGRESS: '行程中',
  ARRIVED: '已到达',
  CLOSED: '已结束',
  CANCELLED: '已取消',
  EXPIRED: '已过期',
})

/**
 * HTML 转义（Web 版新增，小程序没有此风险面）。
 * 为什么必须存在：聊天内容、备注、昵称等都是用户自由文本，页面用 innerHTML 渲染；
 * 服务端 TextSanitizer 是第一道防线，但客户端渲染前转义是 XSS 的最后防线，二者缺一不可。
 * 所有插值进 innerHTML 的动态字符串都必须先过本函数——这是硬性约定，Review 会查。
 */
export function escapeHTML(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/**
 * 事件时间展示（聊天消息用）：今天显示 HH:mm，更早显示 MM-DD HH:mm。
 * 聊天列表/气泡的时间语义与行程列表不同（更强调"刚刚"），单独成函数避免各页复制粘贴走样。
 */
export function chatTime(iso) {
  const d = new Date(iso)
  const now = new Date()
  const pad = (n) => `${n}`.padStart(2, '0')
  const sameDay = d.getFullYear() === now.getFullYear()
    && d.getMonth() === now.getMonth()
    && d.getDate() === now.getDate()
  return sameDay
    ? `${pad(d.getHours())}:${pad(d.getMinutes())}`
    : `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}
