// 无第三方依赖的 uuid v4（clientMsgId / Idempotency-Key 通用）。
// 独立成文件的原因：request.js（幂等键）与 auth.js（mock 登录固定 code）都需要它，
// 放在任何一方都会造成那一对模块循环引用（request→auth→request），单独拆开最干净。
const HEX = '0123456789abcdef'

export function uuid() {
  let s = ''
  for (let i = 0; i < 36; i++) {
    if (i === 8 || i === 13 || i === 18 || i === 23) {
      s += '-'
    } else if (i === 14) {
      s += '4' // version 4
    } else if (i === 19) {
      // variant 位：8/9/a/b 中随机取一
      s += HEX[(Math.floor(Math.random() * 4) + 8) % 16]
    } else {
      s += HEX[Math.floor(Math.random() * 16)]
    }
  }
  return s
}
