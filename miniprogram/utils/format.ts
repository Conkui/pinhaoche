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
