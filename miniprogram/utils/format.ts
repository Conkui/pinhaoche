// 展示格式化（纯函数，领域计算无副作用 —— docs/ARCHITECTURE.md 函数式约定）。

/** UTC 时间字符串 → 本地展示（后端一律 UTC 存储，客户端转换时区）。 */
export function formatTime(iso: string): string {
  const d = new Date(iso)
  const pad = (n: number) => `${n}`.padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export function formatClock(iso: string): string {
  const d = new Date(iso)
  const pad = (n: number) => `${n}`.padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 剩余座位（架构文档示例纯函数）：负数钳制为 0。 */
export const remainingSeats = (memberCount: number, capacity: number): number =>
  Math.max(0, capacity - memberCount)

/** 剩余距离展示：OCR/导入数据必须带更新时间，不能冒充实时数据（规范 §19）。 */
export function formatDistance(km: number | null | undefined, updatedAt?: string | null): string {
  if (km == null) {
    return ''
  }
  const base = `距目的地约 ${km} km`
  return updatedAt ? `${base} · ${formatTime(updatedAt)}更新` : base
}

export function waitingText(minutes: number | null | undefined): string {
  if (minutes == null) {
    return '等待时间不确定'
  }
  return `愿意等待 ${minutes} 分钟`
}

/**
 * 相对路径 → 可加载的绝对 URL（纯函数，无副作用，便于单测）。
 *
 * 用途：后端 `app_user.avatar` 存的是相对路径（RFC-011：如 `/files/xxx.jpg`），
 * 而小程序 `<image src>` 只能加载绝对地址，必须拼上服务端 origin（config.SERVER_ORIGIN）。
 * 把 origin 作为参数传入而不是在本函数里 import config，是为了让本函数保持纯函数
 * （同一入参恒定同输出），测试与复用都不依赖运行环境。
 *
 * 已是绝对地址时原样返回：http(s) 是服务端/cdn 地址，wxfile:// 是本机临时文件
 * （chooseAvatar 选择后、上传前的预览路径），data: 是内联图片；这三种都不该被拼接。
 * 重复调用结果一致（幂等），页面重渲染时反复调用安全。
 */
export function absoluteUrl(path: string | null | undefined, origin: string): string {
  if (!path) {
    return ''
  }
  if (/^(https?:\/\/|wxfile:\/\/|data:)/.test(path)) {
    return path
  }
  // origin 尾部 '/' 由调用方传参决定，这里统一剥掉，避免出现 `http://host//files/x.jpg`
  const base = origin.endsWith('/') ? origin.slice(0, -1) : origin
  return `${base}/${path.replace(/^\/+/, '')}`
}

/** 状态 → 列表页可读标签。 */
export const STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  PUBLISHED: '待同行',
  MATCHING: '匹配中',
  BOOKED: '已打上',
  IN_PROGRESS: '行程中',
  ARRIVED: '已到达',
  CLOSED: '已结束',
  CANCELLED: '已取消',
  EXPIRED: '已过期',
}
