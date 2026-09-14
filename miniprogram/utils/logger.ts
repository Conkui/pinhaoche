// utils/logger.ts：轻量分级日志器 —— 全局异常捕获（app.ts onError / onUnhandledRejection / onPageNotFound）的统一出口。
// 设计约束（为什么这么写）：
// 1. 模块顶层零副作用：app.ts 在 onLaunch 早期就会用到 logger，顶层若执行 wx 调用/同步 IO，
//    要么拖慢冷启动，要么在部分基础库环境直接抛错，让"记录日志"本身成为启动崩溃源。
//    因此顶层只有纯常量与函数定义，wx.request 只在 error 实际触发时才调用。
// 2. 只有 error 级别上报后端：info/warn 高频且低价值（路由跳转、降级提示等），
//    若也上报会刷爆 /client-logs 与开发者工具 Network 面板——用户诉求是"控制台少爆红"，
//    上报通道只留给真正需要事后排查的关键异常。
// 3. 上报必须静默失败：logger 是全局兜底防线的最后一环，若它在 onError 里再抛异常，
//    会形成 onError→logger→throw→onError 的递归爆红。wx.request 收到非 2xx（后端 /client-logs
//    未实现时返回 404/401）只走 success 回调（statusCode 非 2xx）不会抛；网络失败走 fail，
//    仅 console.debug 留痕——success/fail 里绝不写抛错逻辑。
// 4. 所有公共方法内部 try/catch 兜底：日志系统自身绝不能成为新的崩溃源。

import { BASE_URL } from '../config'

type LogLevel = 'error' | 'warn' | 'info'

// /client-logs 请求体（后端收集端点下批次补；当前 404 属预期且静默，见交付说明 Known Issues）。
// 用 type 别名而非 interface：对象字面量类型才有隐式索引签名，才能赋给 wx.request 的 IAnyObject。
type ClientLogPayload = {
  level: LogLevel
  tag: string
  message: string
  stack?: string
  page: string
  ts: number
}

// 输出/上报长度上限：App.onError 传入的字符串可能带几十行堆栈，截断保证 payload 轻量、控制台摘要精简。
const MAX_MESSAGE_LEN = 500
const MAX_STACK_LEN = 2000

interface NormalizedError {
  message: string
  stack?: string
}

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null
}

/** 把各种异常形态（Error / string / 带 message 的对象 / 任意 rejection reason）收敛为 { message, stack? }。 */
function normalizeError(input: unknown): NormalizedError {
  // App.onError 官方类型就是 string，内容是"错误信息+堆栈"整串：首行当摘要，其余行归入 stack。
  if (typeof input === 'string') {
    const br = input.indexOf('\n')
    return br > 0 ? { message: input.slice(0, br), stack: input.slice(br + 1) } : { message: input }
  }
  if (input instanceof Error) {
    return { message: input.message, stack: input.stack }
  }
  // onUnhandledRejection 的 reason 运行时可能是任意值（throw 'foo' / reject({message})）——unknown 收窄后提取。
  if (isRecord(input) && typeof input.message === 'string') {
    return { message: input.message, stack: typeof input.stack === 'string' ? input.stack : undefined }
  }
  return { message: String(input) }
}

function truncate(text: string, max: number): string {
  return text.length <= max ? text : `${text.slice(0, max)}…(已截断，原长${text.length})`
}

/** 当前页面路径；页面栈未建立（冷启动 / 入口页即 404）时返回 'app'。 */
export function currentRoute(): string {
  try {
    const pages = getCurrentPages()
    const last = pages[pages.length - 1]
    return last && last.route ? last.route : 'app'
  } catch {
    return 'app'
  }
}

/** 尽力而为上报（仅 error 级别调用）。success/fail 全部吞掉：非 2xx 只进 success 不抛错；网络失败只 console.debug 留痕。 */
function reportToServer(payload: ClientLogPayload): void {
  wx.request({
    url: `${BASE_URL}/client-logs`,
    method: 'POST',
    data: payload,
    // 刻意不带 Authorization：日志上报是诊断旁路，不能因 token 缺失/过期而不可用，
    // 也不能触发 401 强制重登逻辑（否则记录日志反而打断用户主流程）。
    success: () => {}, // 后端端点未上线时这里是 404/401，属预期，静默
    fail: () => {
      console.debug('[logger] 日志上报失败（网络不可达或后端未部署），已忽略')
    },
  })
}

function emit(level: LogLevel, tag: string, err: unknown, extra?: unknown): void {
  // 兜底 try/catch：即使格式化/IO 出错也绝不向调用方（onError）反抛，避免递归爆红。
  try {
    const { message, stack } = normalizeError(err)
    // 控制台只打"精简摘要"（tag + message [+ extra]），全量堆栈留给上报通道，缓解控制台刷屏。
    const summary = `[phc:${level}] [${tag}] ${truncate(message, MAX_MESSAGE_LEN)}`
    const args: unknown[] = extra === undefined ? [summary] : [summary, extra]
    if (level === 'error') {
      console.error(...args)
      reportToServer({
        level,
        tag,
        message: truncate(message, MAX_MESSAGE_LEN),
        stack: stack ? truncate(stack, MAX_STACK_LEN) : undefined,
        page: currentRoute(),
        ts: Date.now(),
      })
    } else if (level === 'warn') {
      console.warn(...args)
    } else {
      console.info(...args)
    }
  } catch {
    // 完全吞掉：日志系统自身不允许成为新的异常源
  }
}

export const logger = {
  /** error：控制台精简摘要 + 上报关键日志到后端 /client-logs（静默失败）。 */
  error(tag: string, err: unknown, extra?: unknown): void {
    emit('error', tag, err, extra)
  },
  /** warn：仅本地（页 404 兜底、可恢复降级等，不上报降噪）。 */
  warn(tag: string, err: unknown, extra?: unknown): void {
    emit('warn', tag, err, extra)
  },
  /** info：仅本地。 */
  info(tag: string, message: unknown, extra?: unknown): void {
    emit('info', tag, message, extra)
  },
}
