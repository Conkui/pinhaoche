// Ride 服务：列表/详情/发布/加入/状态迁移/导入（openapi /rides 系列）。
import { request } from './request'

export type RideStatus =
  | 'DRAFT' | 'PUBLISHED' | 'MATCHING' | 'BOOKED' | 'IN_PROGRESS'
  | 'ARRIVED' | 'CLOSED' | 'CANCELLED' | 'EXPIRED'

export interface PlaceRef {
  type: 'PRESET' | 'CUSTOM'
  placeId?: number | null
  text: string
}

export interface Ride {
  id: number
  ownerId: number
  origin: PlaceRef
  destination: PlaceRef
  departTime: string
  memberCount: number
  capacity: number
  status: RideStatus
  plateNumber?: string | null
  provider?: string | null
  remainingDistance?: number | null
  distanceUpdatedAt?: string | null
  waitingMinutes?: number | null
  version: number
  remark?: string | null
  /** RFC-012：仅当前用户为车主时由服务端返回；不可据此推断其他订单成员。 */
  memberContacts?: MemberContact[]
}

/**
 * 找拼友页的路线筛选条件。空字符串表示该端不限制；出发地与目的地必须同时命中。
 * 只读取 Ride 创建时固化的地点快照，绝不修改列表对象或回写公共地点。
 */
export interface RouteFilter {
  readonly originText: string
  readonly destinationText: string
}

const normalizeRouteText = (text: string): string => text.trim().toLocaleLowerCase()

/**
 * 在已取得的 PREBOOK 列表上执行纯本地筛选，避免为地点筛选引入未冻结的新接口。
 * 自定义输入采用包含匹配，预设地点名称也自然得到精确匹配；两个条件为 AND。
 */
export const filterRidesByRoute = (rides: readonly Ride[], filter: RouteFilter): Ride[] => {
  const origin = normalizeRouteText(filter.originText)
  const destination = normalizeRouteText(filter.destinationText)
  return rides.filter((ride) => {
    const originMatches = !origin || normalizeRouteText(ride.origin.text).includes(origin)
    const destinationMatches = !destination || normalizeRouteText(ride.destination.text).includes(destination)
    return originMatches && destinationMatches
  })
}

export interface MemberContact {
  userId: number
  nickname: string
  avatar?: string | null
}

export interface CreateRideRequest {
  origin: PlaceRef
  destination: PlaceRef
  departTime: string
  memberCount: number
  capacity: number
  remark?: string
  waitingMinutes?: number | null
}

export interface ImportRideRequest {
  provider: 'DIDI' | 'HUAXIAOZHU' | 'OTHER'
  plateNumber: string
  origin: PlaceRef
  destination: PlaceRef
  remainingDistance?: number | null
  waitingMinutes?: number | null
  seats?: number
}

export function listRides(view: 'PREBOOK' | 'BOOKED'): Promise<Ride[]> {
  return request<Ride[]>(`/rides?view=${view}`)
}

export function getRide(rideId: number): Promise<Ride> {
  return request<Ride>(`/rides/${rideId}`)
}

/** RFC-012：仅订单发布者可读取的已加入拼友最小联系卡。 */
export function listMemberContacts(rideId: number): Promise<MemberContact[]> {
  return request<MemberContact[]>(`/rides/${rideId}/member-contacts`)
}

/** RFC-008：我发布的全部行程（含 CANCELLED/EXPIRED/CLOSED 终态），按创建时间倒序。 */
export function listMine(): Promise<Ride[]> {
  return request<Ride[]>('/rides/mine')
}

/** 发布预打车。idempotencyKey 由页面生成并持有：失败重试复用同一个 key。 */
export function createRide(req: CreateRideRequest, idempotencyKey: string): Promise<Ride> {
  return request<Ride>('/rides', { method: 'POST', data: req, idempotencyKey })
}

export function joinRide(rideId: number, idempotencyKey: string): Promise<Ride> {
  return request<Ride>(`/rides/${rideId}/join`, { method: 'POST', idempotencyKey })
}

/** 显式状态迁移（取消 / 不再等人 / 到达 / 完成）。失败重试复用 key。 */
export function transitRide(rideId: number, targetStatus: RideStatus, version: number,
                            idempotencyKey: string): Promise<Ride> {
  return request<Ride>(`/rides/${rideId}/transitions`, {
    method: 'POST',
    data: { targetStatus, version },
    idempotencyKey,
  })
}

/** 导入"已打上"（RFC-004）：字段经用户确认后提交。 */
export function importRide(req: ImportRideRequest, idempotencyKey: string): Promise<Ride> {
  return request<Ride>('/rides/import', { method: 'POST', data: req, idempotencyKey })
}
