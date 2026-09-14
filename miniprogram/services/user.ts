// 用户资料服务（RFC-011）：头像昵称填写能力的落地端。
// 本文件是小程序侧唯一允许出现"读写本地文件 + 网络写操作 + 回写登录态"副作用的地方，
// 页面只负责收集输入与展示结果（AGENTS.md：副作用收敛在 service 边界）。
import { request } from './request'

/** 与 openapi.yaml `UserProfile` 一致（RFC-011：昵称必填，avatar 为服务端相对路径，可为空）。 */
export interface UserProfile {
  id: number
  nickname: string
  avatar?: string
}

/**
 * app 实例上本模块依赖的最小结构（结构化类型，避免 user → app 的强耦合；
 * 与 request.ts 的 LoginCapableApp 同一写法，用 type alias 而非 interface
 * 才能满足 getApp<T extends IAnyObject> 的约束，且全程无 any）。
 */
type ProfileCapableApp = {
  globalData: { user: UserProfile | null }
}

/** 登录态缓存 key（与 app.ts / request.ts 中的 phc_user 同一份，改动需三处同步）。 */
const USER_STORAGE_KEY = 'phc_user'

/**
 * 头像压缩质量：仅对 jpg 生效（微信 compressImage 的 quality 对 png 无效，见官方文档），
 * 因此 png 截图类头像的体积只能靠客户端大小拦截兜底（见 MAX_AVATAR_BASE64_LENGTH）。
 */
const AVATAR_QUALITY = 70

/**
 * base64 字符串长度上限（约 2.8MB，字符数而非字节数）。
 * 后端上限是**解码后原始字节 ≤ 2 MiB**，base64 长度 = ceil(字节/3)*4 ≈ 原始字节 × 4/3，
 * 2 MiB → 2796204 字符；取 2.8e6 留一点余量，既不误杀合法图片，又能在发送前
 * 拦掉必然 400 的超大图——省掉一次"上传几 MB 再被拒绝"的白等（弱网下可达数十秒）。
 */
const MAX_AVATAR_BASE64_LENGTH = 2.8e6

const ERR_AVATAR_TOO_LARGE = '头像太大，请换一张图片'
const ERR_NOTHING_TO_UPDATE = '没有需要保存的修改'

/**
 * 头像本地读取失败（临时文件被系统清理 / 权限异常）：重试多少次都一样，页面必须清掉这次选择让用户重选。
 *
 * 为什么定义成 Error 子类、而不是让页面比较 message 字符串（Review 第二轮 P2）：
 * 文案是会被人改的展示层内容，也可能与后端返回的某条 400 文案恰好相同——一旦撞车，
 * 就会出现"后端校验失败 → 前端却清空用户刚选好的头像"这种静默错判，且极难排查。
 * instanceof 判定与文案彻底解耦，文案只用于展示。
 */
export class AvatarReadError extends Error {
  constructor() {
    super('头像读取失败，请重新选择头像')
    this.name = 'AvatarReadError'
  }
}

/** 是否为"头像临时文件不可读"这类失败（页面据此清空已选头像，避免用户卡在"怎么点保存都失败"）。 */
export function isAvatarReadFailure(err: unknown): boolean {
  return err instanceof AvatarReadError
}

/**
 * 压缩图片：成功返回压缩后的临时路径；失败**降级返回原图路径**。
 *
 * 为什么可以降级：压缩只是"减小体积、提高弱网上传成功率"的优化，不是正确性前提——
 * 压缩失败常见于基础库不支持/文件格式特殊，此时原图绝大多数仍在上限内，
 * 直接上传体验远好于让用户反复重选。真正的体积兜底是发送前的 base64 长度拦截，
 * 那条防线不依赖压缩是否成功，所以降级不会把必然失败的请求放出去。
 */
function compressAvatar(src: string): Promise<string> {
  return new Promise((resolve) => {
    wx.compressImage({
      src,
      quality: AVATAR_QUALITY,
      success: (res) => resolve(res.tempFilePath),
      fail: () => resolve(src),
    })
  })
}

/**
 * 本地文件 → base64 字符串。
 * 指定 encoding='base64' 时 data 为 string，但类型定义是 `string | ArrayBuffer` 联合，
 * 因此必须运行时判定（严格模式禁止用断言掩盖）；拿到 ArrayBuffer 说明基础库未按
 * 声明行事，属异常，按读取失败处理而不是硬转字符串（硬转会得到 "[object ArrayBuffer]" 之类的垃圾数据）。
 */
function readAsBase64(filePath: string): Promise<string> {
  return new Promise((resolve, reject) => {
    wx.getFileSystemManager().readFile({
      filePath,
      encoding: 'base64',
      success: (res: WechatMiniprogram.ReadFileSuccessCallbackResult) => {
        if (typeof res.data === 'string' && res.data.length > 0) {
          resolve(res.data)
          return
        }
        reject(new AvatarReadError())
      },
      fail: () => reject(new AvatarReadError()),
    })
  })
}

/**
 * 就地回写本地登录态（storage + 内存）。
 *
 * 为什么必须在这里回写：`phc_user` 是"我的"页、后续页面以及冷启动 app.ts 恢复 globalData.user
 * 共用的同一份缓存；服务端保存成功却不更新它，页面刷新后展示的仍是旧昵称/旧头像，
 * 用户会认为"保存没生效"并反复重试。
 * request.ts 不做这件事——它只负责 HTTP 语义（鉴权/幂等/重登），不知道也不会关心业务缓存键。
 */
function persistProfile(profile: UserProfile): void {
  try {
    wx.setStorageSync(USER_STORAGE_KEY, profile)
  } catch (e) {
    // storage 写失败（配额/隐私模式）不影响服务端已保存的事实：仅告警，
    // 下次冷启动会由 /auth/login 或本页重新拉取覆盖，不能让保存流程因此判失败。
    console.warn('[user] 资料已保存但本地缓存写入失败', e)
  }
  try {
    getApp<ProfileCapableApp>().globalData.user = profile
  } catch (e) {
    // getApp 未就绪等同步异常同上：降级为"本次会话内存未同步"，不阻断成功返回
    console.warn('[user] 资料已保存但内存登录态回写失败', e)
  }
}

/**
 * 更新当前用户资料（RFC-011：`PUT /users/me`，幂等替换）。
 *
 * 入参字段缺省即"不改动"：未提供的字段不会出现在请求体里（后端语义：字段缺省保持原值）。
 * 头像链路：本地临时路径 → 压缩（失败降级原图）→ base64 → **发送前体积拦截** → PUT。
 * 失败一律 reject 中文可读 Error/HttpError，交由页面 toast 展示（页面不解析 HTTP 细节之外的东西）。
 */
export function updateProfile(input: { nickname?: string; avatarTempPath?: string }): Promise<UserProfile> {
  const nickname = input.nickname
  const avatarTempPath = input.avatarTempPath

  // 先并行不了：压缩+读取是本地 IO，必须在组装 body 前完成
  const avatarTask: Promise<string | null> = avatarTempPath
    ? compressAvatar(avatarTempPath)
        .then(readAsBase64)
        .then((base64) => {
          if (base64.length > MAX_AVATAR_BASE64_LENGTH) {
            // 发送前拦截：后端上限 2 MiB 原始字节，超限必然 400，
            // 与其让用户白等一次上传，不如立刻给出可操作的提示
            throw new Error(ERR_AVATAR_TOO_LARGE)
          }
          return base64
        })
    : Promise.resolve(null)

  return avatarTask.then((avatarBase64) => {
    const data: { nickname?: string; avatarBase64?: string } = {}
    if (nickname && nickname.length > 0) {
      data.nickname = nickname
    }
    if (avatarBase64) {
      data.avatarBase64 = avatarBase64
    }
    if (!data.nickname && !data.avatarBase64) {
      // 空请求体后端会返回 400 VALIDATION_ERROR；这里提前 reject 成同样的可读提示，
      // 不为一个必然失败的请求建立连接（页面按禁用态本就不该走到这里，属双保险）
      return Promise.reject(new Error(ERR_NOTHING_TO_UPDATE))
    }
    return request<UserProfile>('/users/me', { method: 'PUT', data }).then((profile) => {
      persistProfile(profile)
      return profile
    })
  })
}
