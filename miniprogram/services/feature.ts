import { request } from './request'

/** 实时拼可用性是页面的只读领域状态，网络副作用仅留在 service 边界。 */
export interface RealtimeCarpoolAvailability {
  readonly enabled: boolean
  readonly announcement: string
  readonly updatedAt: string
}

export function getRealtimeCarpoolAvailability(): Promise<RealtimeCarpoolAvailability> {
  return request<RealtimeCarpoolAvailability>('/features/realtime-carpool')
}
