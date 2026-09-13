// 分享文本解析（规范 §17 优先级第 2 级：分享文本/分享信息 → 用户确认）。
// 纯函数、无副作用；解析结果一律只是"预填建议"，必须经用户确认才可提交（规范 §18 低置信度必须确认）。
// 注意：滴滴/花小猪官方接口未接入（OcrProvider/Adapter 留白），本解析只覆盖常见分享文案格式，
// 解析失败返回空字段，绝不允许自动提交未经确认的字段。

export interface ShareParseResult {
  provider: 'DIDI' | 'HUAXIAOZHU' | 'OTHER' | null
  plateNumber: string | null
  remainingDistanceKm: number | null
}

/** 中国大陆车牌（含新能源 6 位序号）。 */
const PLATE_RE = /[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼使领][A-Z][A-HJ-NP-Z0-9]{4,5}[A-HJ-NP-Z0-9挂学警港澳]/

export function parseShareText(text: string): ShareParseResult {
  const result: ShareParseResult = {
    provider: null,
    plateNumber: null,
    remainingDistanceKm: null,
  }
  if (!text) {
    return result
  }

  if (text.includes('滴滴')) {
    result.provider = 'DIDI'
  } else if (text.includes('花小猪')) {
    result.provider = 'HUAXIAOZHU'
  }

  const plate = PLATE_RE.exec(text)
  if (plate) {
    result.plateNumber = plate[0]
  }

  // "剩余 18.6km" / "距离18公里" / "18.6km"
  const dist = /(\d+(?:\.\d+)?)\s*(?:km|公里)/i.exec(text)
  if (dist) {
    const km = Number(dist[1])
    if (!Number.isNaN(km) && km > 0 && km < 2000) {
      result.remainingDistanceKm = km
    }
  }

  return result
}
