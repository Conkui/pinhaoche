// "我的"页：登录信息 + 头像昵称资料编辑（RFC-011）+ 关于。
//
// 头像昵称只能走"头像昵称填写能力"（<button open-type="chooseAvatar"> + <input type="nickname">），
// 这是微信 2022-10-25 之后**唯一**的合规路径。
// ⚠️ 绝对不要改回 wx.getUserProfile / wx.getUserInfo：对未在改版前使用过 getUserInfo 的小程序
//    （本项目基础库 3.4.10 属于该范围），它们一律返回匿名数据（昵称"微信用户" + 灰色默认头像），
//    改回去会让"真实微信昵称/头像"功能静默失效（接口照样成功，只是数据是假的），
//    排查时极易误判为后端问题。
import { SERVER_ORIGIN } from '../../config'
import { HttpError } from '../../services/request'
import { isAvatarReadFailure, updateProfile, UserProfile } from '../../services/user'
import { absoluteUrl } from '../../utils/format'

/** app 实例上本页依赖的最小结构（结构化类型，避免 page → app 强耦合，且全程无 any）。 */
type ProfileCapableApp = {
  globalData: { token: string; user: UserProfile | null }
  ensureLogin: (force?: boolean) => Promise<UserProfile>
}

const app = getApp<ProfileCapableApp>()

const VERSION = 'V1.0.0'

/**
 * 昵称首字符兜底（纯函数）：头像为空或加载失败时用色块展示。
 * 昵称理论上非空（后端校验 1-20 字符），仍兜一个字符，避免色块渲染成空白圆点无从辨认。
 *
 * 用 Array.from 而不是 trimmed[0]：emoji 等 BMP 外字符在 JS 里占两个码元，
 * 直接取 [0] 只会拿到半个代理对，色块会渲染成方块/替换符（如"😀小明"）。
 */
function initialOf(nickname: string): string {
  const chars = Array.from(nickname.trim())
  return chars.length > 0 ? chars[0] : '拼'
}

/**
 * 是否允许提交（纯函数，输入全为 readonly 值类型，页面状态与判定逻辑解耦）。
 * 禁用条件：
 * - 提交中：防止连点导致两次压缩 + 两次上传（重复扣流量、慢网下还可能出现旧请求后到而覆盖新结果）；
 * - 昵称为空且未选新头像：空请求体后端必 400，不该让用户点到；
 * - 无改动（昵称与当前值一致且未选新头像）：一次纯写库的 PUT 没有意义，也避免"点了没反应"的困惑。
 */
function computeCanSave(
  nicknameDraft: string,
  currentNickname: string,
  avatarTempPath: string,
  saving: boolean
): boolean {
  if (saving) {
    return false
  }
  const draft = nicknameDraft.trim()
  const nicknameChanged = draft.length > 0 && draft !== currentNickname
  return nicknameChanged || avatarTempPath.length > 0
}

/** 保存失败 → 用户可读提示。区分网络不可达 / 校验失败 / 登录失效 / 其他服务端错误。 */
function describeError(err: unknown): string {
  if (err instanceof HttpError) {
    if (err.statusCode === 0) {
      return '网络异常，请检查网络后重试'
    }
    if (err.statusCode === 401) {
      return '登录已失效，请重新登录'
    }
    if (err.statusCode === 400) {
      return err.message || '资料不符合要求，请修改后重试'
    }
    return err.message
  }
  if (err instanceof Error && err.message.length > 0) {
    return err.message
  }
  return '保存失败，请重试'
}

Page({
  data: {
    user: null as UserProfile | null,
    /** 昵称输入框内容（真机聚焦时微信会尝试自动填充微信昵称） */
    nicknameDraft: '',
    /** 用户刚选择、尚未上传的临时文件路径（wxfile://tmp_xxx）：仅本机本会话可读 */
    avatarTempPath: '',
    /** 服务端头像的绝对 URL（相对路径 + SERVER_ORIGIN 拼得），空表示未设置 */
    avatarUrl: '',
    /** 头像兜底色块里显示的首字符 */
    avatarInitial: '拼',
    canSave: false,
    saving: false,
    /**
     * 是否存在未保存的本地编辑。用途：本页是 tab 页，用户选好头像后切到别的 tab 再回来会再次触发
     * onShow；无此标记就会用服务端旧数据把刚选的临时头像覆盖掉（用户视角＝选的图凭空消失）。
     */
    draftDirty: false,
    version: VERSION,
  },

  onShow() {
    app
      .ensureLogin()
      .then((user) => {
        // 有未保存编辑时只刷新 user/头像 URL，不动草稿（原因见 draftDirty 注释）
        this.applyUser(user, !this.data.draftDirty)
      })
      .catch(() => {
        wx.showToast({ title: '登录失败，下拉重试', icon: 'none' })
      })
  },

  onPullDownRefresh() {
    wx.stopPullDownRefresh()
    this.onShow()
  },

  /** 服务端返回的最新资料 → 页面状态。resetDraft=false 时保留用户正在编辑的内容。 */
  applyUser(user: UserProfile, resetDraft: boolean) {
    // 昵称输入框默认为空（不回显旧昵称）：头像昵称能力的"一键填入微信昵称"以空输入框体验最好，
    // 且旧昵称是占位值"同学XXXX"，回显后用户还得先全选删除才能改成真名；
    // 当前昵称已展示在卡片头部，信息不丢失。是否算"有改动"由 computeCanSave 与服务端值比对得出。
    const nicknameDraft = resetDraft ? '' : this.data.nicknameDraft
    const avatarTempPath = resetDraft ? '' : this.data.avatarTempPath
    this.setData({
      user,
      nicknameDraft,
      avatarTempPath,
      // 后端存相对路径（/files/xxx.jpg），这里拼成可加载的绝对 URL
      avatarUrl: absoluteUrl(user.avatar, SERVER_ORIGIN),
      avatarInitial: initialOf(user.nickname),
      draftDirty: resetDraft ? false : this.data.draftDirty,
      canSave: computeCanSave(nicknameDraft, user.nickname, avatarTempPath, this.data.saving),
    })
  },

  /** 当前服务端昵称（作为"是否改动"的基线），未登录时视为空串。 */
  currentNickname(): string {
    return this.data.user ? this.data.user.nickname : ''
  },

  /**
   * chooseAvatar 回调：拿到本机临时路径后立刻用该路径预览（未上传也先看到效果）。
   * 只暂存路径，不在这里压缩：压缩在 service 提交时按原始路径做，
   * 避免失败重试时压缩"已压缩过的图"造成画质二次劣化（预览用临时路径，原始选择保留）。
   */
  onChooseAvatar(e: WechatMiniprogram.CustomEvent<{ avatarUrl: string }>) {
    const avatarTempPath = e.detail.avatarUrl
    if (!avatarTempPath) {
      // 用户取消选择（部分机型返回空串）：保持原状，不提示也不清空已有选择
      return
    }
    this.setData({
      avatarTempPath,
      draftDirty: true,
      canSave: computeCanSave(this.data.nicknameDraft, this.currentNickname(), avatarTempPath, this.data.saving),
    })
  },

  /** 昵称输入：同步草稿。工具内不会自动填充微信昵称，用户手填也走这里。 */
  onNicknameInput(e: WechatMiniprogram.Input) {
    const nicknameDraft = e.detail.value
    this.setData({
      nicknameDraft,
      draftDirty: true,
      canSave: computeCanSave(nicknameDraft, this.currentNickname(), this.data.avatarTempPath, this.data.saving),
    })
  },

  /**
   * 头像图片加载失败（后端没起 / 文件已被清理 / 换设备后路径失效）：
   * 退回首字符色块，避免保留坏 URL 每次渲染都重试并刷控制台报错。
   * 若当前预览的是用户刚选的临时图，则不清（重选成本高，且临时图失败多为系统权限问题）。
   */
  onAvatarError() {
    if (this.data.avatarTempPath) {
      return
    }
    this.setData({ avatarUrl: '' })
  },

  onSubmit() {
    if (!this.data.canSave) {
      return
    }
    const user = this.data.user
    const nickname = this.data.nicknameDraft.trim()
    const avatarTempPath = this.data.avatarTempPath

    // 只提交真正变化的字段：后端语义是"字段缺省即保持原值"，
    // 传一个和当前值相同的昵称只是无谓写库
    const payload: { nickname?: string; avatarTempPath?: string } = {}
    if (nickname.length > 0 && nickname !== (user ? user.nickname : '')) {
      payload.nickname = nickname
    }
    if (avatarTempPath.length > 0) {
      payload.avatarTempPath = avatarTempPath
    }
    if (!payload.nickname && !payload.avatarTempPath) {
      return
    }

    this.setData({ saving: true, canSave: false })
    updateProfile(payload)
      .then((profile) => {
        wx.showToast({ title: '已保存' })
        // 成功后才清空草稿：失败时保留用户已选头像与昵称，允许直接重试（UI_SPEC 失败可重试）
        this.setData({ saving: false })
        this.applyUser(profile, true)
      })
      .catch((err: unknown) => {
        // 头像临时文件已不可读（系统清理 tmp / 权限异常）：这类失败重试多少次都一样，
        // 必须清掉这次选择让用户重选，否则页面会卡在"怎么点保存都失败"且没有出口。
        // 其他失败（网络/校验/登录失效）保留草稿，用户可直接重试。
        const avatarReadFailed = isAvatarReadFailure(err)
        const avatarTempPath = avatarReadFailed ? '' : this.data.avatarTempPath
        this.setData({
          saving: false,
          avatarTempPath,
          // 失败后恢复可提交：草稿未动，重试即用同一份原始选择重新走压缩/上传
          canSave: computeCanSave(
            this.data.nicknameDraft,
            this.currentNickname(),
            avatarTempPath,
            false
          ),
        })
        wx.showToast({ title: describeError(err), icon: 'none' })
      })
  },

  goMyRides() {
    wx.navigateTo({ url: '/pages/my-rides/index' })
  },

  goFeedback() {
    wx.navigateTo({ url: '/pages/feedback/index' })
  },

  relogin() {
    wx.removeStorageSync('phc_token')
    wx.removeStorageSync('phc_user')
    app.globalData.token = ''
    app.globalData.user = null
    // 换账号后旧账号的草稿（昵称/临时头像）已无意义，清掉标记让 onShow 全量刷新
    this.setData({ draftDirty: false, avatarTempPath: '', nicknameDraft: '' })
    this.onShow()
  },
})
