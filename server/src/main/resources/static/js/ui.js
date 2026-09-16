// 通用 UI 反馈组件：toast / 确认弹窗 / loading 遮罩 / 空态。
// 对应小程序的 wx.showToast / wx.showModal / wx.showLoading——Web 必须自建，
// 集中在这里让所有页面的反馈形态一致（UI_SPEC：失败可重试、二次确认等）。
// 全部挂 body（不在 .phc-app 内）：盖住头部/底栏，且页面容器反复重绘不影响弹层。

import { escapeHTML } from './utils/format.js'

let toastTimer = null

/**
 * 轻提示（对标 wx.showToast，2s 自动消失）。
 * @param {string} message
 * @param {'success'|'error'|'info'} [type] 影响左侧色条与图标语义，文案由调用方给中文。
 */
export function toast(message, type = 'info') {
  const old = document.querySelector('.phc-toast')
  if (old) {
    old.remove()
  }
  const el = document.createElement('div')
  el.className = `phc-toast phc-toast--${type}`
  el.setAttribute('role', 'status')
  el.textContent = message // textContent：toast 文案不需要也不允许 HTML
  document.body.appendChild(el)
  // 双 rAF 强制先完成插入样式，再触发过渡，否则首次插入没有动画
  requestAnimationFrame(() => requestAnimationFrame(() => el.classList.add('phc-toast--show')))
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => el.remove(), 2000)
}

/**
 * 确认弹窗（对标 wx.showModal）：destructive 操作（下架/删除消息等）二次确认的统一入口。
 * @param {{title: string, content?: string, confirmText?: string, cancelText?: string, danger?: boolean}} opts
 * @returns {Promise<boolean>} resolve(true)=确认，resolve(false)=取消/关闭
 */
export function confirmDialog(opts) {
  return new Promise((resolve) => {
    const backdrop = document.createElement('div')
    backdrop.className = 'phc-dialog-backdrop'
    const confirmText = opts.confirmText ?? '确定'
    backdrop.innerHTML = `
      <div class="phc-dialog" role="alertdialog" aria-modal="true" aria-label="${escapeHTML(opts.title)}">
        <div class="phc-dialog__title">${escapeHTML(opts.title)}</div>
        ${opts.content ? `<div class="phc-dialog__content">${escapeHTML(opts.content)}</div>` : ''}
        <div class="phc-dialog__actions">
          <button type="button" class="phc-dialog__btn" data-act="cancel">${escapeHTML(opts.cancelText ?? '取消')}</button>
          <button type="button" class="phc-dialog__btn phc-dialog__btn--confirm${opts.danger ? ' phc-dialog__btn--danger' : ''}" data-act="confirm">${escapeHTML(confirmText)}</button>
        </div>
      </div>`
    const close = (result) => {
      backdrop.remove()
      document.removeEventListener('keydown', onKey)
      resolve(result)
    }
    /** @param {KeyboardEvent} e */
    const onKey = (e) => {
      if (e.key === 'Escape') {
        close(false)
      }
    }
    backdrop.addEventListener('click', (e) => {
      const target = /** @type {HTMLElement} */ (e.target)
      if (target.closest('[data-act="confirm"]')) {
        close(true)
      } else if (target.closest('[data-act="cancel"]') || target === backdrop) {
        // 点按钮或点遮罩都视为取消；弹窗本体上的点击不关闭
        close(false)
      }
    })
    document.addEventListener('keydown', onKey)
    document.body.appendChild(backdrop)
    /** @type {HTMLButtonElement} */ (backdrop.querySelector('[data-act="confirm"]')).focus()
  })
}

let loadingCount = 0

/**
 * loading 遮罩（可重入计数：嵌套调用时最后一个 hideLoading 才真正关闭）。
 * @param {string} [text]
 */
export function showLoading(text = '加载中…') {
  loadingCount += 1
  let el = /** @type {HTMLElement|null} */ (document.querySelector('.phc-loading'))
  if (!el) {
    el = document.createElement('div')
    el.className = 'phc-loading'
    el.innerHTML = `<div class="phc-loading__box"><span class="phc-loading__spinner" aria-hidden="true"></span><span class="phc-loading__text"></span></div>`
    document.body.appendChild(el)
  }
  /** @type {HTMLElement} */ (el.querySelector('.phc-loading__text')).textContent = text
}

export function hideLoading() {
  loadingCount = Math.max(0, loadingCount - 1)
  if (loadingCount === 0) {
    document.querySelector('.phc-loading')?.remove()
  }
}

/**
 * 空态 HTML（列表页统一形态；返回字符串由页面拼进自己的容器）。
 * @param {string} text 主文案
 * @param {string} [hint] 次级提示（如"点击右上角发布"）
 */
export function emptyStateHTML(text, hint = '') {
  return `
    <div class="phc-empty">
      <div class="phc-empty__icon" aria-hidden="true">🚗</div>
      <div class="phc-empty__text">${escapeHTML(text)}</div>
      ${hint ? `<div class="phc-empty__hint">${escapeHTML(hint)}</div>` : ''}
    </div>`
}

/**
 * 列表加载失败态（UI_SPEC：失败必须给重试入口，不能只 toast 了事）。
 * 返回 DOM 节点（含重试绑定，直接 append 进容器）；空态用 emptyStateHTML 字符串即可。
 * @param {string} message 失败文案（通常传 HttpError.message）
 * @param {() => void} onRetry 重试回调（页面一般传自己的 load 函数）
 * @returns {HTMLElement}
 */
export function errorStateHTML(message, onRetry) {
  const wrap = document.createElement('div')
  wrap.innerHTML = `
    <div class="phc-empty">
      <div class="phc-empty__text">加载失败</div>
      <div class="phc-empty__hint">${escapeHTML(message)}</div>
      <button type="button" class="phc-btn phc-btn--secondary phc-empty__retry">重试</button>
    </div>`
  const el = /** @type {HTMLElement} */ (wrap.firstElementChild)
  /** @type {HTMLButtonElement} */ (el.querySelector('.phc-empty__retry')).addEventListener('click', onRetry)
  return el
}
