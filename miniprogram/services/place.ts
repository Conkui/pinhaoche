// 地点服务：GET /places（管理员预设地点，V1 禁止模糊搜索，客户端全量拉取）。
import { request } from './request'

export interface Place {
  id: number
  name: string
  sortOrder: number
}

export function listPlaces(): Promise<Place[]> {
  return request<Place[]>('/places')
}
